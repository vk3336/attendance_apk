package com.amritaglobal.attendance;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME    = "AmritaAttendancePrefs";
    public static final String KEY_BASE_URL  = "base_url";   // e.g. https://espo.egport.com
    public static final String KEY_API_KEY   = "api_key";

    // Legacy keys kept for migration (no longer shown in UI)
    public static final String KEY_ATTENDANCE_URL = "attendance_url";
    public static final String KEY_EMPLOYEE_URL   = "employee_url";

    private TextInputEditText etBaseUrl, etApiKey;
    private MaterialButton btnSave, btnClear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etBaseUrl = findViewById(R.id.etAttendanceUrl);
        etApiKey  = findViewById(R.id.etApiKey);
        btnSave   = findViewById(R.id.btnSave);
        btnClear  = findViewById(R.id.btnClear);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        loadSettings();
        btnSave.setOnClickListener(v -> saveSettings());
        btnClear.setOnClickListener(v -> clearSettings());
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // Migrate: if old attendance_url exists and base_url doesn't, derive base_url
        String baseUrl = prefs.getString(KEY_BASE_URL, "");
        if (baseUrl.isEmpty()) {
            String oldUrl = prefs.getString(KEY_ATTENDANCE_URL, "");
            if (!oldUrl.isEmpty()) {
                // Strip /api/v1/... suffix to get base
                int idx = oldUrl.indexOf("/api/v1");
                baseUrl = idx > 0 ? oldUrl.substring(0, idx) : oldUrl;
            }
        }
        etBaseUrl.setText(baseUrl);
        etApiKey.setText(prefs.getString(KEY_API_KEY, ""));
    }

    private void saveSettings() {
        String baseUrl = etBaseUrl.getText() != null ? etBaseUrl.getText().toString().trim() : "";
        String apiKey  = etApiKey.getText()  != null ? etApiKey.getText().toString().trim()  : "";

        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(KEY_BASE_URL, baseUrl.replaceAll("/+$", ""));
        editor.putString(KEY_API_KEY, apiKey);
        editor.apply();

        Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void clearSettings() {
        etBaseUrl.setText("");
        etApiKey.setText("");
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
        Toast.makeText(this, "🗑️ Settings cleared", Toast.LENGTH_SHORT).show();
    }
}
