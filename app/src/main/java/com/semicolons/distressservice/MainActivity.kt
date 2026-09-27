package com.semicolons.distressservice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Maintain hardware UUID
        val prefs = getSharedPreferences("VoicePulsePrefs", Context.MODE_PRIVATE)
        var deviceUuid = prefs.getString("device_uuid", null)
        if (deviceUuid == null) {
            deviceUuid = UUID.randomUUID().toString()
            prefs.edit().putString("device_uuid", deviceUuid).apply()
        }

        // 2. Request mic/phone/sms permissions upfront
        checkAndRequestRuntimePermissions()

        // 3. Button 1: Takes you directly to Android's Accessibility settings
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Enable VoicePulse under Downloaded Apps", Toast.LENGTH_LONG).show()
        }
1
        // 4. Button 2: Explicitly redirects to the web dashboard
        val btnOpenDashboard = findViewById<Button>(R.id.btnOpenDashboard)
        btnOpenDashboard.setOnClickListener {
            val dashboardUrl = "https://voice-pulse-frontend.vercel.app/?deviceToken=$deviceUuid"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl))
            startActivity(browserIntent)
        }
    }

    private fun checkAndRequestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
}