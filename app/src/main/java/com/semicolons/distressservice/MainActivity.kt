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
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        const val PREFS_NAME = "VoicePulsePrefs"
        const val KEY_DEVICE_UUID = "device_uuid"
        private const val BACKEND_URL = "https://voice-pulse-backend.onrender.com"
        private const val DASHBOARD_BASE_URL = "https://voice-pulse-frontend.vercel.app/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Maintain a single valid canonical UUID across all components
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceUuid = prefs.getString(KEY_DEVICE_UUID, null)
        if (deviceUuid.isNullOrEmpty()) {
            deviceUuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, deviceUuid).apply()
            Log.i(TAG, "Generated persistent hardware UUID: $deviceUuid")
        }

        // 2. Pre-provision the device on the backend to prevent 404/500 errors
        registerDeviceWithBackend(deviceUuid)

        // 3. Request required runtime permissions upfront
        checkAndRequestRuntimePermissions()

        // 4. Accessibility Settings Button
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

        // Long-click to test capture without an active call
        btnAccessibility.setOnLongClickListener {
            Toast.makeText(this, "Testing Mic Capture (Simulated Call)...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, DistressDetectionService::class.java).apply {
                action = "ACTION_START_TEST_LISTENER"
            }
            startService(intent)
            true
        }

        // 5. Open Web Dashboard Button with dynamic token query param
        val btnOpenDashboard = findViewById<Button>(R.id.btnOpenDashboard)
        btnOpenDashboard.setOnClickListener {
            val dashboardUrl = "$DASHBOARD_BASE_URL?deviceToken=$deviceUuid"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl))
            startActivity(browserIntent)
        }
    }

    private fun registerDeviceWithBackend(uuidString: String) {
        thread(start = true, name = "DevicePreRegThread") {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$BACKEND_URL/api/users/register-device")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; utf-8")
                    setRequestProperty("Accept", "application/json")
                }

                val payload = JSONObject().apply {
                    put("deviceUuid", uuidString)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                Log.i(TAG, "Pre-provisioned device ($uuidString). Status: ${conn.responseCode}")
            } catch (e: Exception) {
                Log.w(TAG, "Device pre-registration deferred: ${e.message}")
            } finally {
                conn?.disconnect()
            }
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

        if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.SEND_SMS), 101)
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