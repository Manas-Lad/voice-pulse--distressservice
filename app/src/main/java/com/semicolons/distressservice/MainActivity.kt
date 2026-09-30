package com.semicolons.distressservice

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "VoicePulsePrefs"
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val DASHBOARD_BASE_URL = "https://voice-pulse-frontend.vercel.app/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Maintain deterministic hardware device UUID
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceUuid = prefs.getString(KEY_DEVICE_UUID, null)
        if (deviceUuid.isNullOrEmpty()) {
            deviceUuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, deviceUuid).commit()
        }

        // 2. Request mic, location, telephony, and SMS runtime permissions upfront
        checkAndRequestRuntimePermissions()

        // 3. Button: Opens Accessibility Settings page
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        btnAccessibility.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "VoicePulse Service is already ACTIVE", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Locate 'DistressDetectionService' under Downloaded/Installed Apps and enable it",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        // 4. Button: Opens Web Dashboard passing the verified deviceToken
        val btnOpenDashboard = findViewById<Button>(R.id.btnOpenDashboard)
        btnOpenDashboard.setOnClickListener {
            val dashboardUrl = "$DASHBOARD_BASE_URL?deviceToken=$deviceUuid"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl))
            startActivity(browserIntent)
        }
    }

    private fun checkAndRequestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedServiceName = packageName

        for (service in enabledServices) {
            val resolveInfo = service.resolveInfo ?: continue
            val serviceInfo = resolveInfo.serviceInfo ?: continue
            if (serviceInfo.packageName.equals(expectedServiceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(
                    this,
                    "Microphone, Phone State, and Location permissions are required for distress detection.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}