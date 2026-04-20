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

    private static final String ATTENDANCE_ENTITY = "CAttendance";
    private static final String ATTACHMENT_ENTITY = "Attachment";

    private final Context context;
    private final OkHttpClient client;

    // ── Callbacks ────────────────────────────────────────────────────────────

    public interface ApiCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface EmployeeCallback {
        void onSuccess(List<Employee> employees);
        void onError(String error);
    }

    /** Holds today's attendance state for an employee */
    public static class AttendanceState {
        public String recordId;          // null = no record yet today
        public boolean checkedIn;
        public boolean lunchOut;
        public boolean lunchIn;
        public boolean checkedOut;
    }

    public interface AttendanceStateCallback {
        void onSuccess(AttendanceState state);
        void onError(String error);
    }

    /** Simple employee model */
    public static class Employee {
        public final String id;
        public final String name;
        public Employee(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    public AttendanceApiHelper(Context context) {
        this.context = context;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String getBaseUrl() {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(SettingsActivity.KEY_BASE_URL, "").replaceAll("/+$", "");
    }

    private String getApiKey() {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(SettingsActivity.KEY_API_KEY, "");
    }

    private boolean isConfigured() {
        return !getBaseUrl().isEmpty() && !getApiKey().isEmpty();
    }

    private Request.Builder baseRequest(String url) {
        return new Request.Builder()
                .url(url)
                .addHeader("X-Api-Key", getApiKey())
                .addHeader("Accept", "application/json");
    }

    /** Format a timestamp to EspoCRM datetime format (UTC) */
    private String toEspoDateTime(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(millis));
    }

    /** Today's date in IST as yyyy-MM-dd */
    private String todayDateIST() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return sdf.format(new Date());
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

    // ── Fetch Employees ──────────────────────────────────────────────────────

    public void fetchEmployees(EmployeeCallback callback) {
        if (!isConfigured()) {
            callback.onError("API not configured. Please go to Settings.");
            return;
        }
        String url = getBaseUrl() + "/api/v1/EmployeeMaster?maxSize=200&offset=0";
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
                            String id = emp.optString("id", "");
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

    // ── Fetch Today's Attendance State ───────────────────────────────────────

    /**
     * Queries CAttendance for today's record for the given employee.
     * Uses a where filter on employeeMasterId + createdAt date range (IST day).
     */
    public void fetchTodayAttendance(String employeeMasterId, AttendanceStateCallback callback) {
        if (!isConfigured()) {
            callback.onError("API not configured.");
            return;
        }

        // Build date range for today in IST (convert to UTC for query)
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        String today = sdf.format(new Date());

        // URL-encode the where filter for EspoCRM API
        String url = getBaseUrl() + "/api/v1/" + ATTENDANCE_ENTITY
                + "?maxSize=5&offset=0"
                + "&where[0][type]=equals&where[0][attribute]=employeeMasterId&where[0][value]=" + employeeMasterId
                + "&where[1][type]=today&where[1][attribute]=createdAt"
                + "&orderBy=createdAt&order=desc";

        Request request = baseRequest(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try {
                    AttendanceState state = new AttendanceState();
                    if (!response.isSuccessful()) {
                        // Return empty state (no record yet)
                        callback.onSuccess(state);
                        return;
                    }
                    String body = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(body);
                    JSONArray list = json.optJSONArray("list");
                    if (list == null || list.length() == 0) {
                        callback.onSuccess(state); // no record today
                        return;
                    }
                    // Take the most recent record
                    JSONObject rec = list.getJSONObject(0);
                    state.recordId = rec.optString("id", null);
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

    // ── Upload Selfie ────────────────────────────────────────────────────────

    public interface UploadCallback {
        void onSuccess(String attachmentId, String attachmentName);
        void onError(String error);
    }

    /**
     * Uploads a selfie image as an EspoCRM Attachment and returns its ID.
     */
    public void uploadSelfie(Uri selfieUri, String fieldName, UploadCallback callback) {
        if (selfieUri == null) { callback.onSuccess(null, null); return; }
        new Thread(() -> {
            try {
                InputStream inputStream = context.getContentResolver().openInputStream(selfieUri);
                if (inputStream == null) { callback.onError("Cannot open image"); return; }
                byte[] imageBytes = readAllBytes(inputStream);
                inputStream.close();

                String fileName = fieldName + "_" + System.currentTimeMillis() + ".jpg";

                // EspoCRM attachment upload via multipart
                RequestBody body = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("name", fileName)
                        .addFormDataPart("type", "image/jpeg")
                        .addFormDataPart("relatedType", ATTENDANCE_ENTITY)
                        .addFormDataPart("field", fieldName)
                        .addFormDataPart("file", fileName,
                                RequestBody.create(imageBytes, MediaType.parse("image/jpeg")))
                        .build();

                Request request = baseRequest(getBaseUrl() + "/api/v1/" + ATTACHMENT_ENTITY)
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        callback.onError("Upload failed: " + response.code());
                        return;
                    }
                    String respBody = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(respBody);
                    String id = json.optString("id", null);
                    String name = json.optString("name", fileName);
                    callback.onSuccess(id, name);
                }
            } catch (Exception e) {
                callback.onError("Upload error: " + e.getMessage());
            }
        }).start();
    }

    // ── Submit / Update Attendance ───────────────────────────────────────────

    /**
     * Creates a new attendance record (Check In).
     */
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

                Request request = baseRequest(getBaseUrl() + "/api/v1/" + ATTENDANCE_ENTITY)
                        .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        callback.onSuccess("Check In recorded");
                    } else {
                        String err = response.body() != null ? response.body().string() : "";
                        callback.onError("Server error " + response.code() + ": " + err);
                    }
                }
            } catch (Exception e) {
                callback.onError("Error: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Updates an existing attendance record with the given attendance type fields.
     * attendanceType: "lunchOut", "lunchIn", "checkOut"
     */
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
                        if (selfieId != null) {
                            body.put("lunchOutSelfieId", selfieId);
                            body.put("lunchOutSelfieName", selfieName);
                        }
                        break;
                    case "lunchIn":
                        body.put("lunchInAt", toEspoDateTime(timestampMillis));
                        if (selfieId != null) {
                            body.put("lunchInSelfieId", selfieId);
                            body.put("lunchInSelfieName", selfieName);
                        }
                        break;
                    case "checkOut":
                        body.put("checkOutAt", toEspoDateTime(timestampMillis));
                        if (selfieId != null) {
                            body.put("checkOutSelfieId", selfieId);
                            body.put("checkOutSelfieName", selfieName);
                        }
                        break;
                    default:
                        callback.onError("Unknown attendance type: " + attendanceType);
                        return;
                }

                Request request = baseRequest(getBaseUrl() + "/api/v1/" + ATTENDANCE_ENTITY + "/" + recordId)
                        .put(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        callback.onSuccess(attendanceType + " recorded");
                    } else {
                        String err = response.body() != null ? response.body().string() : "";
                        callback.onError("Server error " + response.code() + ": " + err);
                    }
                }
            } catch (Exception e) {
                callback.onError("Error: " + e.getMessage());
            }
        }).start();
    }
}
