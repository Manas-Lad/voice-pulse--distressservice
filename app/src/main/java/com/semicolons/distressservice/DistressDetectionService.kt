package com.semicolons.distressservice

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

@Suppress("DEPRECATION")
class DistressDetectionService : AccessibilityService() {

    companion object {
        private const val TAG = "DistressService"
        private const val SAMPLE_RATE = 16000
        private const val BACKEND_URL = "https://voice-pulse-backend.onrender.com"
        private const val PREFS_NAME = "VoicePulsePrefs"
        private const val KEY_DEVICE_UUID = "device_uuid"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private lateinit var audioEnhanceManager: AudioEnhancementManager
    private lateinit var voskDetector: VoskKeywordDetector
    private lateinit var contextEngine: ContextVerificationEngine

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "DistressDetectionService connected.")

        audioEnhanceManager = AudioEnhancementManager()
        voskDetector = VoskKeywordDetector(applicationContext)
        contextEngine = ContextVerificationEngine(applicationContext)

        thread(start = true, name = "ModelInitThread") {
            voskDetector.initialize()
            fetchCustomSecretCodes()
        }

        registerCallListener()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Service onInterrupt called.")
    }

    private fun registerCallListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        phoneStateListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.i(TAG, "Active call detected (OFFHOOK). Starting audio listener.")
                        startAudioCapture()
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.i(TAG, "Call ended or idle. Halting audio listener.")
                        stopAudioCapture()
                    }
                    TelephonyManager.CALL_STATE_RINGING -> {
                        Log.d(TAG, "Device ringing.")
                    }
                }
            }
        }

        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register call listener: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startAudioCapture() {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufferSize.coerceAtLeast(SAMPLE_RATE * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord could not initialize.")
                return
            }

            audioRecord?.let { audioEnhanceManager.attachHardwareEffects(it) }
            audioRecord?.startRecording()
            isRecording = true

            recordingThread = thread(start = true, name = "AudioProcessingWorker") {
                processAudioStream(bufferSize)
            }

            Log.i(TAG, "Audio capture pipeline active.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
        }
    }

    private fun processAudioStream(bufferSize: Int) {
        val audioBuffer = ShortArray(bufferSize / 2)

        while (isRecording && !Thread.currentThread().isInterrupted) {
            val record = audioRecord ?: break
            val readSamples = record.read(audioBuffer, 0, audioBuffer.size)

            if (readSamples > 0) {
                audioEnhanceManager.boostPcmAudioBuffer(audioBuffer, readSamples)

                val result = voskDetector.processAudio(audioBuffer, readSamples)

                if (result.triggered) {
                    Log.w(TAG, "Keyword recognized: \"${result.matchedWord}\" in sentence: \"${result.fullSentence}\"")

                    val isAuthenticDistress = contextEngine.evaluateSentence(
                        recognizedSentence = result.fullSentence,
                        matchedCodeword = result.matchedWord
                    )

                    if (isAuthenticDistress) {
                        Log.e(TAG, "AUTHENTIC DISTRESS CONFIRMED. Dispatching emergency alerts.")
                        triggerEmergencyAlert(result.matchedWord, result.fullSentence)
                    } else {
                        Log.i(TAG, "Keyword suppressed as benign contextual conversation.")
                    }
                } else if (result.fullSentence.isNotEmpty()) {
                    contextEngine.recordNormalSentence(result.fullSentence)
                }
            }
        }
    }

    @Synchronized
    private fun stopAudioCapture() {
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        audioEnhanceManager.release()
        Log.i(TAG, "Audio capture pipeline stopped.")
    }

    @SuppressLint("MissingPermission")
    private fun triggerEmergencyAlert(matchedWord: String, fullHypothesis: String) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    val lat = location?.latitude ?: 0.0
                    val lng = location?.longitude ?: 0.0
                    dispatchEmergencyEvent(matchedWord, fullHypothesis, lat, lng)
                    fetchAndSendEmergencySms(matchedWord, lat, lng)
                }
                .addOnFailureListener {
                    dispatchEmergencyEvent(matchedWord, fullHypothesis, 0.0, 0.0)
                    fetchAndSendEmergencySms(matchedWord, 0.0, 0.0)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Location query failed: ${e.message}")
            dispatchEmergencyEvent(matchedWord, fullHypothesis, 0.0, 0.0)
            fetchAndSendEmergencySms(matchedWord, 0.0, 0.0)
        }
    }

    private fun dispatchEmergencyEvent(word: String, contextText: String, lat: Double, lng: Double) {
        thread(start = true, name = "AlertDispatchWorker") {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val deviceUuid = prefs.getString(KEY_DEVICE_UUID, "UNKNOWN_DEVICE") ?: "UNKNOWN_DEVICE"

            try {
                val url = URL("$BACKEND_URL/api/alerts/trigger")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val payload = JSONObject().apply {
                    put("deviceUuid", deviceUuid)
                    put("triggerWord", word)
                    put("transcript", contextText)
                    put("latitude", lat)
                    put("longitude", lng)
                    put("timestamp", System.currentTimeMillis())
                }

                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

                val responseCode = conn.responseCode
                Log.i(TAG, "Emergency alert POST completed with response code: $responseCode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to POST emergency alert to backend: ${e.message}", e)
            }
        }
    }

    /**
     * Fetches contacts scoped to this device UUID and sends distress SMS.
     */
    private fun fetchAndSendEmergencySms(triggerWord: String, lat: Double, lng: Double) {
        thread(start = true, name = "EmergencySmsWorker") {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val deviceUuid = prefs.getString(KEY_DEVICE_UUID, null) ?: return@thread

            try {
                val url = URL("$BACKEND_URL/api/users/device/$deviceUuid/contacts")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val contactsArray = JSONArray(response)
                    val phoneNumbers = mutableListOf<String>()

                    for (i in 0 until contactsArray.length()) {
                        val contactObj = contactsArray.getJSONObject(i)
                        val alertsEnabled = contactObj.optBoolean("emergencyAlerts", true)
                        val phone = contactObj.optString("phone", "").trim()
                        if (alertsEnabled && phone.isNotEmpty()) {
                            phoneNumbers.add(phone)
                        }
                    }

                    if (phoneNumbers.isEmpty()) {
                        Log.w(TAG, "No emergency contacts found for device $deviceUuid")
                        return@thread
                    }

                    val locationPart = if (lat != 0.0 || lng != 0.0) {
                        "\nLocation: https://maps.google.com/?q=$lat,$lng"
                    } else {
                        ""
                    }

                    val smsBody = "EMERGENCY SOS: A distress signal was triggered ('$triggerWord'). Please check on me.$locationPart"

                    val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        applicationContext.getSystemService(SmsManager::class.java)
                    } else {
                        SmsManager.getDefault()
                    }

                    for (phoneNumber in phoneNumbers) {
                        try {
                            val parts = smsManager.divideMessage(smsBody)
                            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                            Log.i(TAG, "Emergency SMS dispatched to: $phoneNumber")
                        } catch (smsEx: Exception) {
                            Log.e(TAG, "Failed sending SMS to $phoneNumber: ${smsEx.message}", smsEx)
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to retrieve contacts for SMS: HTTP ${conn.responseCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during emergency SMS pipeline: ${e.message}", e)
            }
        }
    }

    private fun fetchCustomSecretCodes() {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceUuid = prefs.getString(KEY_DEVICE_UUID, null) ?: return

        try {
            val url = URL("$BACKEND_URL/api/config/secret-codes?deviceUuid=$deviceUuid")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(response)
                val codesArray: JSONArray = json.optJSONArray("secretCodes") ?: JSONArray()

                val customWords = mutableListOf<String>()
                for (i in 0 until codesArray.length()) {
                    customWords.add(codesArray.getString(i))
                }

                if (customWords.isNotEmpty()) {
                    voskDetector.updateCustomCodewords(customWords)
                    Log.i(TAG, "Loaded custom secret trigger words: $customWords")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not sync custom secret codes: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioCapture()
        voskDetector.close()
        contextEngine.close()

        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        Log.i(TAG, "DistressDetectionService destroyed.")
    }
}