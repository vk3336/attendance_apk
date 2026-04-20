package com.amritaglobal.attendance;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME         = "AmritaAttendancePrefs";
    public static final String KEY_ATTENDANCE_URL = "attendance_url";   // full URL e.g. https://espo.egport.com/api/v1/CAttendance
    public static final String KEY_MASTER_URL     = "master_url";       // full URL e.g. https://espo.egport.com/api/v1/CEmployeeMaster
    public static final String KEY_API_KEY        = "api_key";

    // Legacy key — kept so old installs don't break
    public static final String KEY_BASE_URL = "base_url";

    private TextInputEditText etAttendanceUrl, etEmployeeUrl, etApiKey;
    private MaterialButton btnSave, btnClear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etAttendanceUrl = findViewById(R.id.etAttendanceUrl);
        etEmployeeUrl   = findViewById(R.id.etEmployeeUrl);
        etApiKey        = findViewById(R.id.etApiKey);
        btnSave         = findViewById(R.id.btnSave);
        btnClear        = findViewById(R.id.btnClear);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        loadSettings();
        btnSave.setOnClickListener(v -> saveSettings());
        btnClear.setOnClickListener(v -> clearSettings());
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etAttendanceUrl.setText(prefs.getString(KEY_ATTENDANCE_URL, ""));
        etEmployeeUrl.setText(prefs.getString(KEY_MASTER_URL, ""));
        etApiKey.setText(prefs.getString(KEY_API_KEY, ""));
    }

    private void saveSettings() {
        String attendanceUrl = etAttendanceUrl.getText() != null ? etAttendanceUrl.getText().toString().trim() : "";
        String masterUrl     = etEmployeeUrl.getText()   != null ? etEmployeeUrl.getText().toString().trim()   : "";
        String apiKey        = etApiKey.getText()        != null ? etApiKey.getText().toString().trim()        : "";

        if (attendanceUrl.isEmpty() || masterUrl.isEmpty() || apiKey.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(KEY_ATTENDANCE_URL, attendanceUrl);
        editor.putString(KEY_MASTER_URL, masterUrl);
        editor.putString(KEY_API_KEY, apiKey);
        editor.apply();

        Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void clearSettings() {
        etAttendanceUrl.setText("");
        etEmployeeUrl.setText("");
        etApiKey.setText("");
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
        Toast.makeText(this, "🗑️ Settings cleared", Toast.LENGTH_SHORT).show();
    }
}
