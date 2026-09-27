package com.semicolons.distressservice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
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

    private val permissionRequestCode = 101
    private val requiredPermissions = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.SEND_SMS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (allPermissionsGranted()) {
            onPermissionsReady()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, permissionRequestCode)
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode && allPermissionsGranted()) {
            onPermissionsReady()
        }
    }

    private fun onPermissionsReady() {
        val prefs = getSharedPreferences("VoicePulsePrefs", Context.MODE_PRIVATE)
        var deviceUuid = prefs.getString("device_uuid", null)

        if (deviceUuid == null) {
            deviceUuid = UUID.randomUUID().toString()
            prefs.edit().putString("device_uuid", deviceUuid).apply()

            // Asynchronously register device in backend
            thread {
                registerDeviceOnServer(deviceUuid)
            }
        }

        // Start background distress service
        val serviceIntent = Intent(this, DistressDetectionService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            try {
                startService(serviceIntent)
            } catch (ex: Exception) {
                Log.e("MainActivity", "Failed to start distress service", ex)
            }
        }

        // Redirect user to the Vercel site carrying their device token
        // Hitting root with ?deviceToken ensures the root router handles entry cleanly
        val dashboardUrl = "https://voicepulse.vercel.app/?deviceToken=$deviceUuid"
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(browserIntent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Unable to open browser", e)
        }

        finish()
    }

    private fun registerDeviceOnServer(uuid: String) {
        try {
            val url = URL("https://voice-pulse-backend.onrender.com/api/users/register-device")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("deviceUuid", uuid)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            Log.d("MainActivity", "Device registration response: $responseCode")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error registering device on server", e)
        }
    }
}