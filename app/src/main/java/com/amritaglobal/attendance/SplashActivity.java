package com.amritaglobal.attendance;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DURATION_MS = 2200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hide action bar for full splash experience
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        setContentView(R.layout.activity_splash);

        ImageView ivLogo       = findViewById(R.id.ivSplashLogo);
        TextView  tvCompany    = findViewById(R.id.tvSplashCompany);
        TextView  tvTagline    = findViewById(R.id.tvSplashTagline);

        // Logo zoom-out animation
        Animation zoomOut = AnimationUtils.loadAnimation(this, R.anim.splash_zoom_out);
        ivLogo.startAnimation(zoomOut);

        // Company name + tagline fade-in from below
        Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.splash_text_fade_in);
        tvCompany.startAnimation(fadeIn);
        tvTagline.startAnimation(fadeIn);

        // Navigate to MainActivity after splash duration
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            // Smooth transition — no jarring flash
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, SPLASH_DURATION_MS);
    }

    @Override
    public void onBackPressed() {
        // Prevent back press during splash
    }
}
