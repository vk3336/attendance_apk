package com.amritaglobal.attendance;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    // Views
    private Spinner spinnerEmployee;
    private RadioGroup radioGroupAttendance;
    private RadioButton rbCheckIn, rbLunchOut, rbLunchIn, rbCheckOut;
    private TextView tvDate, tvTime, tvLat, tvLng, tvAddress;
    private TextView tvDateTime, tvLatLng;
    private View mapHintLayout;
    private ImageView ivSelfiePreview;
    private View cameraPlaceholder;
    private MaterialButton btnTakeSelfie, btnRetakeSelfie, btnGetLocation, btnSubmit;
    private ImageButton btnRefresh, btnSettings;
    private WebView webViewMap;

    // Data
    private String selectedEmployee = null;
    private String selectedEmployeeName = null;
    private List<AttendanceApiHelper.Employee> employeeList = new ArrayList<>();
    private double currentLat = 0, currentLng = 0;
    private boolean locationFetched = false;
    private Uri selfieUri = null;
    private long frozenTimestamp = 0;
    private boolean isSubmitting = false;

    // Location
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    // Clock
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;
    private boolean clockRunning = false;

    // Background executor — fixed pool, no unbounded thread creation
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    // Camera
    private Uri cameraImageUri;
    private File cameraImageFile; // track actual file for safe deletion
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private static final String KEY_CAMERA_URI  = "camera_image_uri";
    private static final String KEY_SELFIE_URI  = "selfie_uri";
    private static final String KEY_CAMERA_PATH = "camera_image_path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        setupClock();
        startClock();
        setupCamera();
        setupLocationClient();
        setupButtons();
        setupMap();
        fetchLocation();
        loadEmployees();

        // Restore camera URI after activity recreation (e.g. killed by OS on Android 9/10)
        if (savedInstanceState != null) {
            String uriStr  = savedInstanceState.getString(KEY_CAMERA_URI);
            String selfStr = savedInstanceState.getString(KEY_SELFIE_URI);
            String path    = savedInstanceState.getString(KEY_CAMERA_PATH);
            if (uriStr != null)  cameraImageUri  = Uri.parse(uriStr);
            if (selfStr != null) selfieUri        = Uri.parse(selfStr);
            if (path != null)    cameraImageFile  = new File(path);
        }
    }

    private void initViews() {
        spinnerEmployee    = findViewById(R.id.spinnerEmployee);
        radioGroupAttendance = findViewById(R.id.radioGroupAttendance);
        rbCheckIn          = findViewById(R.id.rbCheckIn);
        rbLunchOut         = findViewById(R.id.rbLunchOut);
        rbLunchIn          = findViewById(R.id.rbLunchIn);
        rbCheckOut         = findViewById(R.id.rbCheckOut);
        tvDate             = findViewById(R.id.tvDate);
        tvTime             = findViewById(R.id.tvTime);
        tvDateTime         = findViewById(R.id.tvDateTime);
        tvLatLng           = findViewById(R.id.tvLatLng);
        tvLat              = findViewById(R.id.tvLat);
        tvLng              = findViewById(R.id.tvLng);
        tvAddress          = findViewById(R.id.tvAddress);
        mapHintLayout      = findViewById(R.id.mapHintLayout);
        ivSelfiePreview    = findViewById(R.id.ivSelfiePreview);
        cameraPlaceholder  = findViewById(R.id.cameraPlaceholder);
        btnTakeSelfie      = findViewById(R.id.btnTakeSelfie);
        btnRetakeSelfie    = findViewById(R.id.btnRetakeSelfie);
        btnGetLocation     = findViewById(R.id.btnGetLocation);
        btnSubmit          = findViewById(R.id.btnSubmit);
        btnRefresh         = findViewById(R.id.btnRefresh);
        btnSettings        = findViewById(R.id.btnSettings);
        webViewMap         = findViewById(R.id.webViewMap);
    }

    // ── Employees ─────────────────────────────────────────────────────────────

    private void loadEmployees() {
        List<String> loading = new ArrayList<>();
        loading.add("Loading employees...");
        spinnerEmployee.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, loading));
        spinnerEmployee.setEnabled(false);

        AttendanceApiHelper apiHelper = new AttendanceApiHelper(this);
        apiHelper.fetchEmployees(new AttendanceApiHelper.EmployeeCallback() {
            @Override public void onSuccess(List<AttendanceApiHelper.Employee> employees) {
                runOnUiThread(() -> { employeeList = employees; setupEmployeeSpinner(employees); });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    spinnerEmployee.setEnabled(true);
                    List<String> err = new ArrayList<>();
                    err.add("⚠ Failed to load");
                    spinnerEmployee.setAdapter(new ArrayAdapter<>(MainActivity.this,
                            android.R.layout.simple_spinner_item, err));
                    Toast.makeText(MainActivity.this, "Could not load employees: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setupEmployeeSpinner(List<AttendanceApiHelper.Employee> employees) {
        List<String> names = new ArrayList<>();
        names.add("-- Select Employee --");
        for (AttendanceApiHelper.Employee emp : employees) names.add(emp.name);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names) {
            @Override public boolean isEnabled(int position) { return position != 0; }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerEmployee.setAdapter(adapter);
        spinnerEmployee.setEnabled(true);

        spinnerEmployee.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == 0) {
                    selectedEmployee = null; selectedEmployeeName = null;
                    disableAttendanceControls();
                } else {
                    AttendanceApiHelper.Employee emp = employees.get(pos - 1);
                    selectedEmployee = emp.id; selectedEmployeeName = emp.name;
                    enableAttendanceControls();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {
                selectedEmployee = null; selectedEmployeeName = null;
                disableAttendanceControls();
            }
        });
    }

    // ── Attendance controls ───────────────────────────────────────────────────

    private void disableAttendanceControls() {
        rbCheckIn.setEnabled(false); rbLunchOut.setEnabled(false);
        rbLunchIn.setEnabled(false); rbCheckOut.setEnabled(false);
        radioGroupAttendance.clearCheck();
        resetRadioLabels();
        startClock();
    }

    private void resetRadioLabels() {
        rbCheckIn.setText("✅  Check In");
        rbLunchOut.setText("🍽️  Lunch Out");
        rbLunchIn.setText("🍴  Lunch In");
        rbCheckOut.setText("🚪  Check Out");
    }

    private void enableAttendanceControls() {
        frozenTimestamp = System.currentTimeMillis();
        stopClock();
        AttendanceApiHelper apiHelper = new AttendanceApiHelper(this);
        apiHelper.fetchTodayAttendance(selectedEmployee, new AttendanceApiHelper.AttendanceStateCallback() {
            @Override public void onSuccess(AttendanceApiHelper.AttendanceState state) {
                runOnUiThread(() -> updateAttendanceButtons(state));
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    rbCheckIn.setEnabled(true);
                    rbLunchOut.setEnabled(false); rbLunchIn.setEnabled(false); rbCheckOut.setEnabled(false);
                    Toast.makeText(MainActivity.this, "Could not fetch status: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void updateAttendanceButtons(AttendanceApiHelper.AttendanceState state) {
        resetRadioLabels();
        if (state.checkedIn  && state.checkInTime  != null) rbCheckIn.setText("✅  Check In     " + state.checkInTime);
        if (state.lunchOut   && state.lunchOutTime != null) rbLunchOut.setText("🍽️  Lunch Out   " + state.lunchOutTime);
        if (state.lunchIn    && state.lunchInTime  != null) rbLunchIn.setText("🍴  Lunch In     " + state.lunchInTime);
        if (state.checkedOut && state.checkOutTime != null) rbCheckOut.setText("🚪  Check Out  " + state.checkOutTime);

        if (!state.checkedIn) {
            rbCheckIn.setEnabled(true);
            rbLunchOut.setEnabled(false); rbLunchIn.setEnabled(false); rbCheckOut.setEnabled(false);
        } else if (state.checkedOut) {
            rbCheckIn.setEnabled(false); rbLunchOut.setEnabled(false);
            rbLunchIn.setEnabled(false); rbCheckOut.setEnabled(false);
            Toast.makeText(this, "✅ Attendance completed for today", Toast.LENGTH_LONG).show();
        } else if (state.lunchOut && !state.lunchIn) {
            rbCheckIn.setEnabled(false); rbLunchOut.setEnabled(false);
            rbLunchIn.setEnabled(true);  rbCheckOut.setEnabled(false);
        } else if (state.lunchIn) {
            rbCheckIn.setEnabled(false); rbLunchOut.setEnabled(false);
            rbLunchIn.setEnabled(false); rbCheckOut.setEnabled(true);
        } else {
            rbCheckIn.setEnabled(false); rbLunchOut.setEnabled(true);
            rbLunchIn.setEnabled(false); rbCheckOut.setEnabled(true);
        }
    }

    // ── Clock ─────────────────────────────────────────────────────────────────

    private void setupClock() {
        clockRunnable = new Runnable() {
            @Override public void run() {
                updateDateTime();
                clockHandler.postDelayed(this, 1000);
            }
        };
    }

    private void startClock() {
        if (!clockRunning) { clockRunning = true; clockHandler.post(clockRunnable); }
    }

    private void stopClock() {
        clockRunning = false; clockHandler.removeCallbacks(clockRunnable);
    }

    @SuppressLint("SetTextI18n")
    private void updateDateTime() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        SimpleDateFormat df = new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH);
        SimpleDateFormat tf = new SimpleDateFormat("hh:mm:ss a", Locale.ENGLISH);
        df.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        tf.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        tvDate.setText(df.format(cal.getTime()));
        tvTime.setText(tf.format(cal.getTime()) + " IST");
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private void setupCamera() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    if (Boolean.TRUE.equals(result.getOrDefault(Manifest.permission.CAMERA, false)))
                        launchCamera();
                    else
                        Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
                });

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(), success -> {
                    // On Android 9/10 low-RAM devices, the activity can be killed while
                    // the camera is open. cameraImageUri is restored from savedInstanceState,
                    // but if it's still null here, we cannot proceed.
                    if (cameraImageUri == null) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "Camera error: please try again", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    if (success) {
                        selfieUri = cameraImageUri;
                        final Uri uriToLoad = cameraImageUri;
                        // Load a downsampled preview only — never load full-res into memory
                        executor.execute(() -> {
                            android.graphics.Bitmap preview = null;
                            try {
                                InputStream is = getContentResolver().openInputStream(uriToLoad);
                                if (is == null) {
                                    runOnUiThread(() -> Toast.makeText(this, "Failed to load preview", Toast.LENGTH_SHORT).show());
                                    return;
                                }
                                BitmapFactory.Options opts = new BitmapFactory.Options();
                                opts.inSampleSize = 4; // 1/4 size for preview only
                                preview = BitmapFactory.decodeStream(is, null, opts);
                                is.close();
                                if (preview != null) {
                                    final android.graphics.Bitmap bmp = preview;
                                    runOnUiThread(() -> {
                                        // Recycle old bitmap to free memory before setting new one
                                        android.graphics.drawable.Drawable old = ivSelfiePreview.getDrawable();
                                        if (old instanceof android.graphics.drawable.BitmapDrawable) {
                                            android.graphics.Bitmap oldBmp = ((android.graphics.drawable.BitmapDrawable) old).getBitmap();
                                            ivSelfiePreview.setImageBitmap(null);
                                            if (oldBmp != null && !oldBmp.isRecycled()) oldBmp.recycle();
                                        }
                                        ivSelfiePreview.setImageBitmap(bmp);
                                        ivSelfiePreview.setVisibility(View.VISIBLE);
                                        cameraPlaceholder.setVisibility(View.GONE);
                                        btnRetakeSelfie.setVisibility(View.VISIBLE);
                                        Toast.makeText(this, "Selfie captured!", Toast.LENGTH_SHORT).show();
                                    });
                                } else {
                                    runOnUiThread(() -> Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show());
                                }
                            } catch (Exception e) {
                                if (preview != null && !preview.isRecycled()) preview.recycle();
                                runOnUiThread(() -> Toast.makeText(this, "Failed to load preview", Toast.LENGTH_SHORT).show());
                            }
                        });
                    } else {
                        // User cancelled or camera failed — don't reset the whole UI,
                        // just leave the selfie state as-is so user can try again
                        runOnUiThread(() -> Toast.makeText(this,
                                "Camera cancelled", Toast.LENGTH_SHORT).show());
                    }
                });
    }

    private void launchCamera() {
        try {
            // Delete previous temp file to free storage
            if (cameraImageFile != null && cameraImageFile.exists()) {
                cameraImageFile.delete();
                cameraImageFile = null;
                cameraImageUri = null;
            }
            File imageFile = createImageFile();
            cameraImageFile = imageFile;
            cameraImageUri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider", imageFile);
            cameraLauncher.launch(cameraImageUri);
        } catch (IOException e) {
            Toast.makeText(this, "Error creating image file", Toast.LENGTH_SHORT).show();
        }
    }

    private File createImageFile() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Calendar.getInstance().getTime());
        // getExternalFilesDir can return null on some Android 10 (MIUI) devices —
        // fall back to internal cache dir which is always available
        File storageDir = getExternalFilesDir("Pictures");
        if (storageDir == null || (!storageDir.exists() && !storageDir.mkdirs())) {
            storageDir = new File(getCacheDir(), "images");
            if (!storageDir.exists()) storageDir.mkdirs();
        }
        return File.createTempFile("SELFIE_" + ts + "_", ".jpg", storageDir);
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private void setupLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    @SuppressLint({"SetTextI18n", "MissingPermission"})
    private void fetchLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_CODE);
            return;
        }
        // Remove old callback before registering new one
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
        btnGetLocation.setText("Fetching...");
        btnGetLocation.setEnabled(false);
        tvAddress.setText("📍 Fetching address...");

        // Show last known immediately
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, loc -> {
            if (loc != null) updateLocation(loc.getLatitude(), loc.getLongitude());
        });

        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setWaitForAccurateLocation(false).setMinUpdateIntervalMillis(2000).setMaxUpdates(1).build();

        locationCallback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult result) {
                // Remove immediately to prevent further callbacks
                fusedLocationClient.removeLocationUpdates(locationCallback);
                locationCallback = null;
                if (result != null && !result.getLocations().isEmpty()) {
                    Location loc = result.getLocations().get(0);
                    runOnUiThread(() -> {
                        updateLocation(loc.getLatitude(), loc.getLongitude());
                        btnGetLocation.setText("🔄  Refresh Location");
                        btnGetLocation.setEnabled(true);
                    });
                } else {
                    runOnUiThread(() -> {
                        if (!locationFetched)
                            Toast.makeText(MainActivity.this, "Could not get location", Toast.LENGTH_SHORT).show();
                        btnGetLocation.setText("🔄  Refresh Location");
                        btnGetLocation.setEnabled(true);
                    });
                }
            }
        };
        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
    }

    @SuppressLint("SetTextI18n")
    private void updateLocation(double lat, double lng) {
        currentLat = lat; currentLng = lng; locationFetched = true;
        tvLat.setText(String.format(Locale.ENGLISH, "%.6f", lat));
        tvLng.setText(String.format(Locale.ENGLISH, "%.6f", lng));
        if (mapHintLayout != null) mapHintLayout.setVisibility(View.GONE);
        loadMap(lat, lng);
        fetchAddress(lat, lng);
    }

    private void fetchAddress(double lat, double lng) {
        executor.execute(() -> {
            try {
                Geocoder geocoder = new Geocoder(this, Locale.ENGLISH);
                List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    Address a = addresses.get(0);
                    for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                        sb.append(a.getAddressLine(i));
                        if (i < a.getMaxAddressLineIndex()) sb.append(", ");
                    }
                    String addr = sb.toString();
                    runOnUiThread(() -> tvAddress.setText("📍 " + addr));
                }
            } catch (Exception e) {
                runOnUiThread(() -> tvAddress.setText("📍 Address unavailable"));
            }
        });
    }

    // ── Map ───────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupMap() {
        WebSettings s = webViewMap.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true);
        webViewMap.setWebViewClient(new WebViewClient());
    }

    private void loadMap(double lat, double lng) {
        if (mapHintLayout != null) mapHintLayout.setVisibility(View.GONE);
        String html = "<!DOCTYPE html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>*{margin:0;padding:0;}html,body,iframe{width:100%;height:100%;border:0;}</style>"
                + "</head><body><iframe src='https://maps.google.com/maps?q=" + lat + "," + lng
                + "&z=16&output=embed' allowfullscreen></iframe></body></html>";
        webViewMap.loadDataWithBaseURL("https://maps.google.com", html, "text/html", "UTF-8", null);
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private void setupButtons() {
        btnRefresh.setOnClickListener(v -> refreshAll());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnGetLocation.setOnClickListener(v -> fetchLocation());
        btnTakeSelfie.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
            else launchCamera();
        });
        btnRetakeSelfie.setOnClickListener(v -> {
            clearSelfie();
            launchCamera();
        });
        btnSubmit.setOnClickListener(v -> submitAttendance());
    }

    private void clearSelfie() {
        // Recycle bitmap to free memory
        android.graphics.drawable.Drawable d = ivSelfiePreview.getDrawable();
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            ivSelfiePreview.setImageBitmap(null);
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        } else {
            ivSelfiePreview.setImageBitmap(null);
        }
        ivSelfiePreview.setVisibility(View.GONE);
        cameraPlaceholder.setVisibility(View.VISIBLE);
        btnRetakeSelfie.setVisibility(View.GONE);
        selfieUri = null;
    }

    private void refreshAll() {
        isSubmitting = false;
        spinnerEmployee.setSelection(0);
        radioGroupAttendance.clearCheck();
        resetRadioLabels();
        tvLat.setText("--"); tvLng.setText("--");
        tvAddress.setText("📍 Fetching address...");
        if (mapHintLayout != null) mapHintLayout.setVisibility(View.VISIBLE);
        webViewMap.loadUrl("about:blank");
        clearSelfie();
        selectedEmployee = null; selectedEmployeeName = null;
        locationFetched = false; currentLat = 0; currentLng = 0; frozenTimestamp = 0;
        startClock();
        fetchLocation();
        loadEmployees();
        Toast.makeText(this, "Refreshed", Toast.LENGTH_SHORT).show();
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    private void submitAttendance() {
        if (isSubmitting) return;
        if (selectedEmployee == null) { Toast.makeText(this, "Please select an employee", Toast.LENGTH_SHORT).show(); return; }
        if (radioGroupAttendance.getCheckedRadioButtonId() == -1) { Toast.makeText(this, "Please select attendance type", Toast.LENGTH_SHORT).show(); return; }
        if (!locationFetched) { Toast.makeText(this, "Please fetch your location first", Toast.LENGTH_SHORT).show(); return; }
        if (selfieUri == null) { Toast.makeText(this, "Please take a selfie first", Toast.LENGTH_SHORT).show(); return; }

        int checkedId = radioGroupAttendance.getCheckedRadioButtonId();
        final String attendanceType;
        if      (checkedId == R.id.rbCheckIn)  attendanceType = "checkIn";
        else if (checkedId == R.id.rbLunchOut) attendanceType = "lunchOut";
        else if (checkedId == R.id.rbLunchIn)  attendanceType = "lunchIn";
        else                                   attendanceType = "checkOut";

        final String selfieField;
        switch (attendanceType) {
            case "checkIn":  selfieField = "checkInSelfie";  break;
            case "lunchOut": selfieField = "lunchOutSelfie"; break;
            case "lunchIn":  selfieField = "lunchInSelfie";  break;
            default:         selfieField = "checkOutSelfie"; break;
        }

        final long now = System.currentTimeMillis(); // capture exact submit moment
        final Uri capturedUri = selfieUri;
        final double lat = currentLat, lng = currentLng;
        final String empId = selectedEmployee, empName = selectedEmployeeName;

        isSubmitting = true;
        btnSubmit.setEnabled(false);
        btnSubmit.setText("Submitting...");

        final AttendanceApiHelper api = new AttendanceApiHelper(this);

        api.fetchTodayAttendance(empId, new AttendanceApiHelper.AttendanceStateCallback() {
            @Override public void onSuccess(AttendanceApiHelper.AttendanceState state) {
                if (!attendanceType.equals("checkIn") && state.recordId == null) {
                    resetSubmitButton("No check-in found for today. Please check in first.");
                    return;
                }
                api.uploadSelfie(capturedUri, selfieField, new AttendanceApiHelper.UploadCallback() {
                    @Override public void onSuccess(String attachmentId, String attachmentName) {
                        // Already on background thread from uploadSelfie — run API call directly
                        AttendanceApiHelper.ApiCallback done = new AttendanceApiHelper.ApiCallback() {
                            @Override public void onSuccess(String msg) {
                                runOnUiThread(() -> {
                                    isSubmitting = false;
                                    Toast.makeText(MainActivity.this, "✅ Attendance submitted!", Toast.LENGTH_LONG).show();
                                    clearSelfie();
                                    resetSubmitButton(null);
                                    radioGroupAttendance.clearCheck();
                                    frozenTimestamp = System.currentTimeMillis();
                                    enableAttendanceControls();
                                });
                            }
                            @Override public void onError(String error) {
                                resetSubmitButton("❌ Error: " + error);
                            }
                        };
                        if (attendanceType.equals("checkIn"))
                            api.createAttendanceSync(empId, empName, now, lat, lng, attachmentId, attachmentName, done);
                        else
                            api.updateAttendanceSync(state.recordId, attendanceType, now, lat, lng, attachmentId, attachmentName, done);
                    }
                    @Override public void onError(String error) {
                        resetSubmitButton("❌ Selfie upload failed: " + error);
                    }
                });
            }
            @Override public void onError(String error) {
                resetSubmitButton("❌ Error checking status: " + error);
            }
        });
    }

    private void resetSubmitButton(String msg) {
        runOnUiThread(() -> {
            isSubmitting = false;
            btnSubmit.setEnabled(true);
            btnSubmit.setText("Submit");
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                startClock();
            }
        });
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Persist URIs so they survive activity kill on low-RAM Android 9/10 devices
        if (cameraImageUri != null)  outState.putString(KEY_CAMERA_URI,  cameraImageUri.toString());
        if (selfieUri != null)       outState.putString(KEY_SELFIE_URI,  selfieUri.toString());
        if (cameraImageFile != null) outState.putString(KEY_CAMERA_PATH, cameraImageFile.getAbsolutePath());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reset submit button only if genuinely stuck mid-submit with no selfie
        // (activity was killed while submitting). Do NOT reset if selfie exists —
        // that means we're returning normally from camera.
        if (isSubmitting && selfieUri == null) {
            isSubmitting = false;
            btnSubmit.setEnabled(true);
            btnSubmit.setText("Submit");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            fetchLocation();
        else if (requestCode == PERMISSION_REQUEST_CODE)
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopClock();
        clockHandler.removeCallbacksAndMessages(null);
        if (locationCallback != null && fusedLocationClient != null) {
            try { fusedLocationClient.removeLocationUpdates(locationCallback); } catch (Exception ignored) {}
            locationCallback = null;
        }
        executor.shutdownNow(); // cancel any pending background tasks
        webViewMap.destroy();
    }
}
