package com.amritaglobal.attendance;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AttendanceApiHelper {

    private final Context context;

    // ── Shared HTTP client — one instance, reuses TCP connections ─────────────
    private static volatile OkHttpClient sharedClient;
    private static OkHttpClient getClient() {
        if (sharedClient == null) {
            synchronized (AttendanceApiHelper.class) {
                if (sharedClient == null) {
                    sharedClient = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .writeTimeout(20, TimeUnit.SECONDS)
                            .connectionPool(new ConnectionPool(5, 60, TimeUnit.SECONDS))
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return sharedClient;
    }

    // ── Shared thread pool — bounded, no unbounded thread creation ────────────
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);

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
        public boolean checkedIn, lunchOut, lunchIn, checkedOut;
        public String checkInTime, lunchOutTime, lunchInTime, checkOutTime;
    }

    public static class Employee {
        public final String id, name;
        public Employee(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public AttendanceApiHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private String getAttendanceUrl() { return pref(SettingsActivity.KEY_ATTENDANCE_URL).replaceAll("/+$", ""); }
    private String getMasterUrl()     { return pref(SettingsActivity.KEY_MASTER_URL).replaceAll("/+$", ""); }
    private String getApiKey()        { return pref(SettingsActivity.KEY_API_KEY); }

    private String pref(String key) {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key, "");
    }

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

    // ── Date helpers ──────────────────────────────────────────────────────────

    private String toEspoDateTime(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(millis));
    }

    private String toIst(String utcStr, SimpleDateFormat parser, SimpleDateFormat istFmt) {
        try { return istFmt.format(parser.parse(utcStr)); } catch (Exception e) { return ""; }
    }

    // ── Fetch Employees ───────────────────────────────────────────────────────

    public void fetchEmployees(EmployeeCallback callback) {
        if (getMasterUrl().isEmpty() || getApiKey().isEmpty()) {
            callback.onError("API not configured. Please go to Settings."); return;
        }
        Request request = baseRequest(getMasterUrl() + "?maxSize=200&offset=0").get().build();
        getClient().newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) { callback.onError("Server error: " + response.code()); return; }
                    String body = response.body() != null ? response.body().string() : "";
                    JSONArray list = new JSONObject(body).getJSONArray("list");
                    List<Employee> employees = new ArrayList<>();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject emp = list.getJSONObject(i);
                        if (!emp.optBoolean("deleted", false)) {
                            String id = emp.optString("id", ""), name = emp.optString("name", "");
                            if (!id.isEmpty() && !name.isEmpty()) employees.add(new Employee(id, name));
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

    // ── Fetch Today's Attendance ──────────────────────────────────────────────

    public void fetchTodayAttendance(String employeeMasterId, AttendanceStateCallback callback) {
        if (!isConfigured()) { callback.onError("API not configured."); return; }

        SimpleDateFormat dateSdf = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        dateSdf.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        String todayDate = dateSdf.format(new Date());

        String url = getAttendanceUrl()
                + "?maxSize=10&offset=0"
                + "&where[0][type]=equals&where[0][attribute]=employeeMasterId&where[0][value]=" + employeeMasterId
                + "&orderBy=createdAt&order=desc";

        getClient().newCall(baseRequest(url).get().build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) {
                try {
                    AttendanceState state = new AttendanceState();
                    if (!response.isSuccessful()) { callback.onSuccess(state); return; }
                    String body = response.body() != null ? response.body().string() : "";
                    JSONArray list = new JSONObject(body).optJSONArray("list");
                    if (list == null || list.length() == 0) { callback.onSuccess(state); return; }

                    SimpleDateFormat recSdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
                    recSdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                    SimpleDateFormat recDateSdf = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
                    recDateSdf.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
                    SimpleDateFormat timeSdf = new SimpleDateFormat("hh:mm:ss a", Locale.ENGLISH);
                    timeSdf.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));

                    for (int i = 0; i < list.length(); i++) {
                        JSONObject rec = list.getJSONObject(i);
                        String createdAt = rec.optString("createdAt", "");
                        if (createdAt.isEmpty()) continue;
                        try {
                            Date recDate = recSdf.parse(createdAt);
                            if (!recDateSdf.format(recDate).equals(todayDate)) continue;

                            state.recordId = rec.optString("id", null);
                            String ciAt = rec.optString("checkInAt",  "");
                            String loAt = rec.optString("lunchOutAt", "");
                            String liAt = rec.optString("lunchInAt",  "");
                            String coAt = rec.optString("checkOutAt", "");

                            state.checkedIn  = isValidTime(ciAt);
                            state.lunchOut   = isValidTime(loAt);
                            state.lunchIn    = isValidTime(liAt);
                            state.checkedOut = isValidTime(coAt);

                            if (state.checkedIn)  state.checkInTime  = toIst(ciAt, recSdf, timeSdf);
                            if (state.lunchOut)   state.lunchOutTime = toIst(loAt, recSdf, timeSdf);
                            if (state.lunchIn)    state.lunchInTime  = toIst(liAt, recSdf, timeSdf);
                            if (state.checkedOut) state.checkOutTime = toIst(coAt, recSdf, timeSdf);
                            break;
                        } catch (Exception ignored) {}
                    }
                    callback.onSuccess(state);
                } catch (Exception e) {
                    callback.onError("Parse error: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    private boolean isValidTime(String val) {
        return val != null && !val.isEmpty() && !val.equals("null");
    }

    // ── Upload Selfie ─────────────────────────────────────────────────────────

    public void uploadSelfie(Uri selfieUri, String fieldName, UploadCallback callback) {
        if (selfieUri == null) { callback.onSuccess(null, null); return; }
        IO.execute(() -> {
            Bitmap bitmap = null;
            try {
                InputStream is = context.getContentResolver().openInputStream(selfieUri);
                if (is == null) { callback.onError("Cannot open image"); return; }

                // Decode at 1/2 size first to reduce memory pressure
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                bitmap = BitmapFactory.decodeStream(is, null, opts);
                is.close();

                if (bitmap == null) { callback.onError("Cannot decode image"); return; }

                // Scale to max 800px wide
                if (bitmap.getWidth() > 800) {
                    int h = (int) (bitmap.getHeight() * (800f / bitmap.getWidth()));
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 800, h, true);
                    bitmap.recycle();
                    bitmap = scaled;
                }

                // Compress to JPEG 75% — typically 80-150KB
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                byte[] imageBytes = baos.toByteArray();
                baos.close();

                String fileName = fieldName + "_" + System.currentTimeMillis() + ".jpg";
                String base64 = "data:image/jpeg;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                JSONObject body = new JSONObject();
                body.put("name", fileName);
                body.put("type", "image/jpeg");
                body.put("relatedType", "CAttendance");
                body.put("field", fieldName);
                body.put("file", base64);

                try (Response response = getClient().newCall(
                        baseRequest(getApiRoot() + "/Attachment")
                                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                                .build()).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "{}";
                    if (!response.isSuccessful()) {
                        callback.onError("Upload failed: " + response.code() + " - " + respBody);
                        return;
                    }
                    JSONObject json = new JSONObject(respBody);
                    callback.onSuccess(json.optString("id", null), json.optString("name", fileName));
                }
            } catch (Exception e) {
                callback.onError("Upload error: " + e.getMessage());
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        });
    }

    // ── Create Attendance (Check In) ──────────────────────────────────────────

    public void createAttendance(String employeeMasterId, String employeeMasterName,
                                  long timestampMillis, double lat, double lng,
                                  String selfieId, String selfieName, ApiCallback callback) {
        if (!isConfigured()) { callback.onError("API not configured."); return; }
        IO.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("employeeMasterId", employeeMasterId);
                body.put("employeeMasterName", employeeMasterName);
                body.put("checkInAt", toEspoDateTime(timestampMillis));
                body.put("checkInOfficeLatitude", lat);
                body.put("checkInOfficeLongitude", lng);
                if (selfieId != null) {
                    body.put("checkInSelfieId", selfieId);
                    body.put("checkInSelfieName", selfieName);
                }
                try (Response r = getClient().newCall(
                        baseRequest(getAttendanceUrl())
                                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                                .build()).execute()) {
                    if (r.isSuccessful()) callback.onSuccess("Check In recorded");
                    else callback.onError("Server error " + r.code() + ": " + (r.body() != null ? r.body().string() : ""));
                }
            } catch (Exception e) { callback.onError("Error: " + e.getMessage()); }
        });
    }

    // ── Update Attendance ─────────────────────────────────────────────────────

    public void updateAttendance(String recordId, String attendanceType,
                                  long timestampMillis, double lat, double lng,
                                  String selfieId, String selfieName, ApiCallback callback) {
        if (!isConfigured()) { callback.onError("API not configured."); return; }
        IO.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                switch (attendanceType) {
                    case "lunchOut":
                        body.put("lunchOutAt", toEspoDateTime(timestampMillis));
                        body.put("lunchStartOfficeLatitude", lat);
                        body.put("lunchStartOfficeLongitude", lng);
                        if (selfieId != null) { body.put("lunchOutSelfieId", selfieId); body.put("lunchOutSelfieName", selfieName); }
                        break;
                    case "lunchIn":
                        body.put("lunchInAt", toEspoDateTime(timestampMillis));
                        body.put("lunchEndOfficeLatitude", lat);
                        body.put("lunchEndOfficeLongitude", lng);
                        if (selfieId != null) { body.put("lunchInSelfieId", selfieId); body.put("lunchInSelfieName", selfieName); }
                        break;
                    case "checkOut":
                        body.put("checkOutAt", toEspoDateTime(timestampMillis));
                        body.put("checkOutOfficeLatitude", lat);
                        body.put("checkOutOfficeLongitude", lng);
                        if (selfieId != null) { body.put("checkOutSelfieId", selfieId); body.put("checkOutSelfieName", selfieName); }
                        break;
                    default:
                        callback.onError("Unknown type: " + attendanceType); return;
                }
                try (Response r = getClient().newCall(
                        baseRequest(getAttendanceUrl() + "/" + recordId)
                                .put(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                                .build()).execute()) {
                    if (r.isSuccessful()) callback.onSuccess(attendanceType + " recorded");
                    else callback.onError("Server error " + r.code() + ": " + (r.body() != null ? r.body().string() : ""));
                }
            } catch (Exception e) { callback.onError("Error: " + e.getMessage()); }
        });
    }
}
