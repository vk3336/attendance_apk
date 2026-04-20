package com.amritaglobal.attendance;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AttendanceApiHelper {

    private final Context context;
    private final OkHttpClient client;

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public interface ApiCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface EmployeeCallback {
        void onSuccess(List<Employee> employees);
        void onError(String error);
    }

    public interface UploadCallback {
        void onSuccess(String attachmentId, String attachmentName);
        void onError(String error);
    }

    public interface AttendanceStateCallback {
        void onSuccess(AttendanceState state);
        void onError(String error);
    }

    public static class AttendanceState {
        public String recordId;
        public boolean checkedIn;
        public boolean lunchOut;
        public boolean lunchIn;
        public boolean checkedOut;
    }

    public static class Employee {
        public final String id;
        public final String name;
        public Employee(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public AttendanceApiHelper(Context context) {
        this.context = context;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // ── Settings helpers ──────────────────────────────────────────────────────

    /** Full attendance URL, e.g. https://espo.egport.com/api/v1/CAttendance */
    private String getAttendanceUrl() {
        return pref(SettingsActivity.KEY_ATTENDANCE_URL).replaceAll("/+$", "");
    }

    /** Full employee master URL, e.g. https://espo.egport.com/api/v1/CEmployeeMaster */
    private String getMasterUrl() {
        return pref(SettingsActivity.KEY_MASTER_URL).replaceAll("/+$", "");
    }

    private String getApiKey() {
        return pref(SettingsActivity.KEY_API_KEY);
    }

    private String pref(String key) {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key, "");
    }

    /**
     * Derives the base API root from the attendance URL.
     * e.g. https://espo.egport.com/api/v1/CAttendance → https://espo.egport.com/api/v1
     */
    private String getApiRoot() {
        String url = getAttendanceUrl();
        int idx = url.lastIndexOf('/');
        return idx > 0 ? url.substring(0, idx) : url;
    }

    private boolean isConfigured() {
        return !getAttendanceUrl().isEmpty() && !getMasterUrl().isEmpty() && !getApiKey().isEmpty();
    }

    private Request.Builder baseRequest(String url) {
        return new Request.Builder()
                .url(url)
                .addHeader("X-Api-Key", getApiKey())
                .addHeader("Accept", "application/json");
    }

    private String toEspoDateTime(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(millis));
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    // ── Fetch Employees ───────────────────────────────────────────────────────

    public void fetchEmployees(EmployeeCallback callback) {
        if (getMasterUrl().isEmpty() || getApiKey().isEmpty()) {
            callback.onError("API not configured. Please go to Settings.");
            return;
        }
        String url = getMasterUrl() + "?maxSize=200&offset=0";
        Request request = baseRequest(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        callback.onError("Server error: " + response.code());
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(body);
                    JSONArray list = json.getJSONArray("list");
                    List<Employee> employees = new ArrayList<>();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject emp = list.getJSONObject(i);
                        if (!emp.optBoolean("deleted", false)) {
                            String id   = emp.optString("id", "");
                            String name = emp.optString("name", "");
                            if (!id.isEmpty() && !name.isEmpty()) {
                                employees.add(new Employee(id, name));
                            }
                        }
                    }
                    callback.onSuccess(employees);
                } catch (Exception e) {
                    callback.onError("Parse error: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    // ── Fetch Today's Attendance State ────────────────────────────────────────

    public void fetchTodayAttendance(String employeeMasterId, AttendanceStateCallback callback) {
        if (!isConfigured()) { callback.onError("API not configured."); return; }

        String url = getAttendanceUrl()
                + "?maxSize=5&offset=0"
                + "&where[0][type]=equals&where[0][attribute]=employeeMasterId&where[0][value]=" + employeeMasterId
                + "&where[1][type]=today&where[1][attribute]=createdAt"
                + "&orderBy=createdAt&order=desc";

        client.newCall(baseRequest(url).get().build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try {
                    AttendanceState state = new AttendanceState();
                    if (!response.isSuccessful()) { callback.onSuccess(state); return; }
                    String body = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(body);
                    JSONArray list = json.optJSONArray("list");
                    if (list == null || list.length() == 0) { callback.onSuccess(state); return; }
                    JSONObject rec = list.getJSONObject(0);
                    state.recordId   = rec.optString("id", null);
                    state.checkedIn  = !rec.isNull("checkInAt");
                    state.lunchOut   = !rec.isNull("lunchOutAt");
                    state.lunchIn    = !rec.isNull("lunchInAt");
                    state.checkedOut = !rec.isNull("checkOutAt");
                    callback.onSuccess(state);
                } catch (Exception e) {
                    callback.onError("Parse error: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    // ── Upload Selfie ─────────────────────────────────────────────────────────

    public void uploadSelfie(Uri selfieUri, String fieldName, UploadCallback callback) {
        if (selfieUri == null) { callback.onSuccess(null, null); return; }
        new Thread(() -> {
            try {
                InputStream inputStream = context.getContentResolver().openInputStream(selfieUri);
                if (inputStream == null) { callback.onError("Cannot open image"); return; }
                byte[] imageBytes = readAllBytes(inputStream);
                inputStream.close();

                String fileName = fieldName + "_" + System.currentTimeMillis() + ".jpg";
                String uploadUrl = getApiRoot() + "/Attachment";

                RequestBody body = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("name", fileName)
                        .addFormDataPart("type", "image/jpeg")
                        .addFormDataPart("relatedType", "CAttendance")
                        .addFormDataPart("field", fieldName)
                        .addFormDataPart("file", fileName,
                                RequestBody.create(imageBytes, MediaType.parse("image/jpeg")))
                        .build();

                try (Response response = client.newCall(baseRequest(uploadUrl).post(body).build()).execute()) {
                    if (!response.isSuccessful()) {
                        callback.onError("Upload failed: " + response.code());
                        return;
                    }
                    JSONObject json = new JSONObject(response.body() != null ? response.body().string() : "{}");
                    callback.onSuccess(json.optString("id", null), json.optString("name", fileName));
                }
            } catch (Exception e) {
                callback.onError("Upload error: " + e.getMessage());
            }
        }).start();
    }

    // ── Create Attendance (Check In) ──────────────────────────────────────────

    public void createAttendance(String employeeMasterId, String employeeMasterName,
                                  long timestampMillis, double lat, double lng,
                                  String selfieId, String selfieName,
                                  ApiCallback callback) {
        if (!isConfigured()) { callback.onError("API not configured."); return; }
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("employeeMasterId", employeeMasterId);
                body.put("employeeMasterName", employeeMasterName);
                body.put("checkInAt", toEspoDateTime(timestampMillis));
                body.put("officeLat", lat);
                body.put("officeLng", lng);
                if (selfieId != null) {
                    body.put("checkInSelfieId", selfieId);
                    body.put("checkInSelfieName", selfieName);
                }
                try (Response response = client.newCall(
                        baseRequest(getAttendanceUrl())
                                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                                .build()).execute()) {
                    if (response.isSuccessful()) callback.onSuccess("Check In recorded");
                    else callback.onError("Server error " + response.code() + ": " +
                            (response.body() != null ? response.body().string() : ""));
                }
            } catch (Exception e) { callback.onError("Error: " + e.getMessage()); }
        }).start();
    }

    // ── Update Attendance (Lunch Out / Lunch In / Check Out) ──────────────────

    public void updateAttendance(String recordId, String attendanceType,
                                  long timestampMillis, double lat, double lng,
                                  String selfieId, String selfieName,
                                  ApiCallback callback) {
        if (!isConfigured()) { callback.onError("API not configured."); return; }
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("officeLat", lat);
                body.put("officeLng", lng);
                switch (attendanceType) {
                    case "lunchOut":
                        body.put("lunchOutAt", toEspoDateTime(timestampMillis));
                        if (selfieId != null) { body.put("lunchOutSelfieId", selfieId); body.put("lunchOutSelfieName", selfieName); }
                        break;
                    case "lunchIn":
                        body.put("lunchInAt", toEspoDateTime(timestampMillis));
                        if (selfieId != null) { body.put("lunchInSelfieId", selfieId); body.put("lunchInSelfieName", selfieName); }
                        break;
                    case "checkOut":
                        body.put("checkOutAt", toEspoDateTime(timestampMillis));
                        if (selfieId != null) { body.put("checkOutSelfieId", selfieId); body.put("checkOutSelfieName", selfieName); }
                        break;
                    default:
                        callback.onError("Unknown attendance type: " + attendanceType);
                        return;
                }
                try (Response response = client.newCall(
                        baseRequest(getAttendanceUrl() + "/" + recordId)
                                .put(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                                .build()).execute()) {
                    if (response.isSuccessful()) callback.onSuccess(attendanceType + " recorded");
                    else callback.onError("Server error " + response.code() + ": " +
                            (response.body() != null ? response.body().string() : ""));
                }
            } catch (Exception e) { callback.onError("Error: " + e.getMessage()); }
        }).start();
    }
}
