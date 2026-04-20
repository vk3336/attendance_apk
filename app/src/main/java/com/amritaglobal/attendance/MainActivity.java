package com.amritaglobal.attendance;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    // Views
    private Spinner spinnerEmployee;
    private RadioGroup radioGroupAttendance;
    private RadioButton rbCheckIn, rbLunchOut, rbLunchIn, rbCheckOut;
    private TextView tvDate, tvTime, tvLat, tvLng, tvAddress, tvMapHint;
    private TextView tvDateTime, tvLatLng; // legacy hidden views
    private View mapHintLayout;
    private ImageView ivSelfiePreview;
    private View cameraPlaceholder;
    private MaterialButton btnTakeSelfie, btnRetakeSelfie, btnGetLocation, btnSubmit;
    private ImageButton btnRefresh, btnSettings;
    private WebView webViewMap;

    // Data
    private String selectedEmployee = null;
    private double currentLat = 0, currentLng = 0;
    private boolean locationFetched = false;
    private Uri selfieUri = null;
    private Bitmap selfieBitmap = null;

    // Location
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    // Clock
    private Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;
    private boolean clockRunning = false;

    // Camera
    private Uri cameraImageUri;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupEmployeeSpinner();
        setupRadioButtons();
        setupClock();
        startClock(); // start clock immediately on open
        setupCamera();
        setupLocationClient();
        setupButtons();
        setupMap();
        fetchLocation(); // auto-fetch location on open
    }

    private void initViews() {
        spinnerEmployee = findViewById(R.id.spinnerEmployee);
        radioGroupAttendance = findViewById(R.id.radioGroupAttendance);
        rbCheckIn = findViewById(R.id.rbCheckIn);
        rbLunchOut = findViewById(R.id.rbLunchOut);
        rbLunchIn = findViewById(R.id.rbLunchIn);
        rbCheckOut = findViewById(R.id.rbCheckOut);
        tvDate = findViewById(R.id.tvDate);
        tvTime = findViewById(R.id.tvTime);
        tvLat = findViewById(R.id.tvLat);
        tvLng = findViewById(R.id.tvLng);
        tvDateTime = findViewById(R.id.tvDateTime); // hidden, legacy
        tvLatLng = findViewById(R.id.tvLatLng);     // hidden, legacy
        tvAddress = findViewById(R.id.tvAddress);
        tvMapHint = findViewById(R.id.tvMapHint);
        mapHintLayout = findViewById(R.id.mapHintLayout);
        ivSelfiePreview = findViewById(R.id.ivSelfiePreview);
        cameraPlaceholder = findViewById(R.id.cameraPlaceholder);
        btnTakeSelfie = findViewById(R.id.btnTakeSelfie);
        btnRetakeSelfie = findViewById(R.id.btnRetakeSelfie);
        btnGetLocation = findViewById(R.id.btnGetLocation);
        btnSubmit = findViewById(R.id.btnSubmit);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnSettings = findViewById(R.id.btnSettings);
        webViewMap = findViewById(R.id.webViewMap);
    }

    private void setupEmployeeSpinner() {
        List<String> employees = new ArrayList<>();
        employees.add("-- Select Employee --");
        employees.add("Vivek");
        employees.add("Archie");
        employees.add("Test");

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, employees) {
            @Override
            public boolean isEnabled(int position) {
                return position != 0;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerEmployee.setAdapter(adapter);

        spinnerEmployee.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    selectedEmployee = null;
                    disableAttendanceControls();
                } else {
                    selectedEmployee = employees.get(position);
                    enableAttendanceControls();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedEmployee = null;
                disableAttendanceControls();
            }
        });
    }

    private void disableAttendanceControls() {
        rbCheckIn.setEnabled(false);
        rbLunchOut.setEnabled(false);
        rbLunchIn.setEnabled(false);
        rbCheckOut.setEnabled(false);
        radioGroupAttendance.clearCheck();
        // clock and location keep running — no reset here
    }

    private void enableAttendanceControls() {
        rbCheckIn.setEnabled(true);
        rbLunchOut.setEnabled(true);
        rbLunchIn.setEnabled(true);
        rbCheckOut.setEnabled(true);
    }

    private void setupRadioButtons() {
        radioGroupAttendance.setOnCheckedChangeListener((group, checkedId) -> {
            // Only one can be selected at a time - handled by RadioGroup automatically
        });
    }

    private void setupClock() {
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                updateDateTime();
                clockHandler.postDelayed(this, 1000);
            }
        };
    }

    private void startClock() {
        if (!clockRunning) {
            clockRunning = true;
            clockHandler.post(clockRunnable);
        }
    }

    private void stopClock() {
        clockRunning = false;
        clockHandler.removeCallbacks(clockRunnable);
    }

    @SuppressLint("SetTextI18n")
    private void updateDateTime() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH);
        SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm:ss a", Locale.ENGLISH);
        dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        timeFormat.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        tvDate.setText(dateFormat.format(cal.getTime()));
        tvTime.setText(timeFormat.format(cal.getTime()) + " IST");
    }

    private void setupCamera() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    Boolean cameraGranted = result.getOrDefault(Manifest.permission.CAMERA, false);
                    if (Boolean.TRUE.equals(cameraGranted)) {
                        launchCamera();
                    } else {
                        Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
                    }
                });

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && cameraImageUri != null) {
                        try {
                            selfieBitmap = MediaStore.Images.Media.getBitmap(
                                    getContentResolver(), cameraImageUri);
                            selfieUri = cameraImageUri;
                            ivSelfiePreview.setImageBitmap(selfieBitmap);
                            ivSelfiePreview.setVisibility(View.VISIBLE);
                            cameraPlaceholder.setVisibility(View.GONE);
                            btnRetakeSelfie.setVisibility(View.VISIBLE);
                            Toast.makeText(this, "Selfie captured!", Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void launchCamera() {
        try {
            File imageFile = createImageFile();
            cameraImageUri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider", imageFile);
            cameraLauncher.launch(cameraImageUri);
        } catch (IOException e) {
            Toast.makeText(this, "Error creating image file", Toast.LENGTH_SHORT).show();
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Calendar.getInstance().getTime());
        String imageFileName = "SELFIE_" + timeStamp + "_";
        File storageDir = getExternalFilesDir("Pictures");
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    private void setupLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    @SuppressLint({"SetTextI18n", "MissingPermission"})
    private void fetchLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    PERMISSION_REQUEST_CODE);
            return;
        }

        btnGetLocation.setText("Fetching...");
        btnGetLocation.setEnabled(false);
        tvAddress.setText("📍 Fetching address...");

        // Use last known location immediately for instant display
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, lastLocation -> {
            if (lastLocation != null) {
                currentLat = lastLocation.getLatitude();
                currentLng = lastLocation.getLongitude();
                locationFetched = true;
                tvLat.setText(String.format(Locale.ENGLISH, "%.6f", currentLat));
                tvLng.setText(String.format(Locale.ENGLISH, "%.6f", currentLng));
                mapHintLayout.setVisibility(View.GONE);
                loadMap(currentLat, currentLng);
                fetchAddress(currentLat, currentLng);
            }
        });

        // Also request a fresh high-accuracy fix
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(1000)
                .setMaxUpdates(1)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                fusedLocationClient.removeLocationUpdates(locationCallback);
                if (locationResult != null && !locationResult.getLocations().isEmpty()) {
                    Location location = locationResult.getLocations().get(0);
                    currentLat = location.getLatitude();
                    currentLng = location.getLongitude();
                    locationFetched = true;
                    runOnUiThread(() -> {
                        tvLat.setText(String.format(Locale.ENGLISH, "%.6f", currentLat));
                        tvLng.setText(String.format(Locale.ENGLISH, "%.6f", currentLng));
                        mapHintLayout.setVisibility(View.GONE);
                        loadMap(currentLat, currentLng);
                        fetchAddress(currentLat, currentLng);
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

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void fetchAddress(double lat, double lng) {
        new Thread(() -> {
            try {
                Geocoder geocoder = new Geocoder(this, Locale.ENGLISH);
                List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                        sb.append(address.getAddressLine(i));
                        if (i < address.getMaxAddressLineIndex()) sb.append(", ");
                    }
                    String fullAddress = sb.toString();
                    runOnUiThread(() -> tvAddress.setText("📍 " + fullAddress));
                }
            } catch (IOException e) {
                runOnUiThread(() -> tvAddress.setText("Address: Unable to fetch"));
            }
        }).start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupMap() {
        WebSettings settings = webViewMap.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        webViewMap.setWebViewClient(new WebViewClient());
    }

    private void loadMap(double lat, double lng) {
        if (mapHintLayout != null) mapHintLayout.setVisibility(View.GONE);
        // Google Maps embed inside a full HTML page — bypasses the iframe-only restriction
        String html = "<!DOCTYPE html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>*{margin:0;padding:0;}html,body,iframe{width:100%;height:100%;border:0;}</style>"
                + "</head><body>"
                + "<iframe src='https://maps.google.com/maps?q=" + lat + "," + lng
                + "&z=16&output=embed' allowfullscreen></iframe>"
                + "</body></html>";
        webViewMap.loadDataWithBaseURL("https://maps.google.com", html, "text/html", "UTF-8", null);
    }

    private void setupButtons() {
        btnRefresh.setOnClickListener(v -> refreshAll());

        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });

        btnGetLocation.setOnClickListener(v -> fetchLocation());

        btnTakeSelfie.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
            } else {
                launchCamera();
            }
        });

        btnRetakeSelfie.setOnClickListener(v -> {
            ivSelfiePreview.setVisibility(View.GONE);
            cameraPlaceholder.setVisibility(View.VISIBLE);
            btnRetakeSelfie.setVisibility(View.GONE);
            selfieUri = null;
            selfieBitmap = null;
            launchCamera();
        });

        btnSubmit.setOnClickListener(v -> submitAttendance());
    }

    private void refreshAll() {
        spinnerEmployee.setSelection(0);
        radioGroupAttendance.clearCheck();
        tvLat.setText("--");
        tvLng.setText("--");
        tvAddress.setText("📍 Fetching address...");
        if (mapHintLayout != null) mapHintLayout.setVisibility(View.VISIBLE);
        webViewMap.loadUrl("about:blank");
        ivSelfiePreview.setVisibility(View.GONE);
        cameraPlaceholder.setVisibility(View.VISIBLE);
        btnRetakeSelfie.setVisibility(View.GONE);
        selfieUri = null;
        selfieBitmap = null;
        selectedEmployee = null;
        locationFetched = false;
        currentLat = 0;
        currentLng = 0;
        startClock();
        fetchLocation();
        Toast.makeText(this, "Refreshed", Toast.LENGTH_SHORT).show();
    }

    private void submitAttendance() {
        if (selectedEmployee == null) {
            Toast.makeText(this, "Please select an employee", Toast.LENGTH_SHORT).show();
            return;
        }
        if (radioGroupAttendance.getCheckedRadioButtonId() == -1) {
            Toast.makeText(this, "Please select attendance type", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!locationFetched) {
            Toast.makeText(this, "Please fetch your location first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selfieUri == null) {
            Toast.makeText(this, "Please take a selfie first", Toast.LENGTH_SHORT).show();
            return;
        }

        int checkedId = radioGroupAttendance.getCheckedRadioButtonId();
        String attendanceType;
        if (checkedId == R.id.rbCheckIn)       attendanceType = "checkIn";
        else if (checkedId == R.id.rbLunchOut) attendanceType = "lunchOut";
        else if (checkedId == R.id.rbLunchIn)  attendanceType = "lunchIn";
        else                                   attendanceType = "checkOut";

        long now = System.currentTimeMillis();
        AttendanceApiHelper apiHelper = new AttendanceApiHelper(this);

        btnSubmit.setEnabled(false);
        btnSubmit.setText("Submitting...");

        // First upload the selfie, then create/update the record
        String selfieField = attendanceType.equals("checkIn") ? "checkInSelfie"
                : attendanceType.equals("lunchOut") ? "lunchOutSelfie"
                : attendanceType.equals("lunchIn")  ? "lunchInSelfie"
                : "checkOutSelfie";

        apiHelper.uploadSelfie(selfieUri, selfieField, new AttendanceApiHelper.UploadCallback() {
            @Override
            public void onSuccess(String attachmentId, String attachmentName) {
                AttendanceApiHelper.ApiCallback done = new AttendanceApiHelper.ApiCallback() {
                    @Override public void onSuccess(String message) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "✅ Attendance submitted successfully!", Toast.LENGTH_LONG).show();
                            btnSubmit.setEnabled(true);
                            btnSubmit.setText("Submit");
                            refreshAll();
                        });
                    }
                    @Override public void onError(String error) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "❌ Error: " + error, Toast.LENGTH_LONG).show();
                            btnSubmit.setEnabled(true);
                            btnSubmit.setText("Submit");
                        });
                    }
                };

                if (attendanceType.equals("checkIn")) {
                    apiHelper.createAttendance(selectedEmployee, selectedEmployee,
                            now, currentLat, currentLng, attachmentId, attachmentName, done);
                } else {
                    // Need today's record ID — fetch state first
                    apiHelper.fetchTodayAttendance(selectedEmployee,
                            new AttendanceApiHelper.AttendanceStateCallback() {
                        @Override public void onSuccess(AttendanceApiHelper.AttendanceState state) {
                            if (state.recordId == null) {
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this,
                                            "No check-in record found for today. Please check in first.",
                                            Toast.LENGTH_LONG).show();
                                    btnSubmit.setEnabled(true);
                                    btnSubmit.setText("Submit");
                                });
                                return;
                            }
                            apiHelper.updateAttendance(state.recordId, attendanceType,
                                    now, currentLat, currentLng, attachmentId, attachmentName, done);
                        }
                        @Override public void onError(String error) {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this,
                                        "❌ Error fetching record: " + error, Toast.LENGTH_LONG).show();
                                btnSubmit.setEnabled(true);
                                btnSubmit.setText("Submit");
                            });
                        }
                    });
                }
            }
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "❌ Selfie upload failed: " + error, Toast.LENGTH_LONG).show();
                    btnSubmit.setEnabled(true);
                    btnSubmit.setText("Submit");
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchLocation();
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopClock();
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}
