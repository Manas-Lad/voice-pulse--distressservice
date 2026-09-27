package com.semicolons.distressservice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.sqrt

class DistressDetectionService : AccessibilityService() {

    private val TAG = "DistressService"

    private var isRecording = false
    private var audioRecord: AudioRecord? = null

    private val sampleRate = 16000
    private val frameSize = 800

    private var consecutiveStressFrames = 0
    private val stressThresholdFrames = 15

    private var consecutiveSilenceFrames = 0
    private val silenceThresholdFrames = 300

    private var logThrottle = 0

    private val observedEvents = mutableSetOf<String>()
    private var sosTriggered = false

    private var voskKeywordDetector: VoskKeywordDetector? = null

    private val serverUrl = "https://voice-pulse-backend.onrender.com/api/alerts"

    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: Any? = null
    private var legacyPhoneStateListener: PhoneStateListener? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Distress Safety Service Connected & Standing By.")
        registerCallStateListener()
    }

    private fun registerCallStateListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
            telephonyCallback = callback
            telephonyManager?.registerTelephonyCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state)
                }
            }
            legacyPhoneStateListener = listener
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call Connected (OFFHOOK)! Starting microphone capture loop.")
                startMonitoring()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d(TAG, "Call Ended (IDLE). Stopping Monitoring.")
                stopMonitoring()
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startMonitoring() {
        if (isRecording) return

        isRecording = true
        consecutiveSilenceFrames = 0
        consecutiveStressFrames = 0
        logThrottle = 0
        observedEvents.clear()
        sosTriggered = false

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferCapacity = maxOf(minBufSize, frameSize * 2)

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferCapacity
            )
            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord initialization error: ${e.message}")
            isRecording = false
            return
        }

        thread {
            Thread.sleep(9000)

            try {
                voskKeywordDetector = VoskKeywordDetector(applicationContext)
                voskKeywordDetector?.initialize()
            } catch (e: Exception) {
                Log.e(TAG, "Vosk initialization failed", e)
            }

            val audioBuffer = ShortArray(frameSize)
            while (isRecording) {
                val read = audioRecord?.read(audioBuffer, 0, frameSize) ?: 0
                if (read > 0) {
                    processAudioChunk(audioBuffer, read)
                } else {
                    Thread.sleep(100)
                }
            }
        }
    }

    private fun processAudioChunk(buffer: ShortArray, readSize: Int) {
        if (sosTriggered) return

        val codeWordDetected = voskKeywordDetector?.processAudio(buffer, readSize) == true
        if (codeWordDetected) {
            val finalSignals = (observedEvents + "CODE_WORD").toList()
            sosTriggered = true
            dispatchDistressAlert(score = 1.0, signals = finalSignals)
            stopMonitoring()
            return
        }

        var sumSquares = 0.0
        for (i in 0 until readSize) {
            sumSquares += buffer[i] * buffer[i]
        }
        val rms = sqrt(sumSquares / readSize)

        var zeroCrossings = 0
        for (i in 1 until readSize) {
            if ((buffer[i] >= 0 && buffer[i - 1] < 0) || (buffer[i] < 0 && buffer[i - 1] >= 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toDouble() / readSize

        var silenceSignal = false
        if (rms < 200.0) {
            consecutiveSilenceFrames++
            if (consecutiveSilenceFrames >= silenceThresholdFrames) {
                silenceSignal = true
            }
        } else {
            consecutiveSilenceFrames = 0
        }

        if (rms > 5000.0 && zcr > 0.05) {
            consecutiveStressFrames++
        } else {
            consecutiveStressFrames = maxOf(0, consecutiveStressFrames - 1)
        }
        val stressSignal = consecutiveStressFrames >= stressThresholdFrames

        logThrottle++
        if (logThrottle % 10 == 0) {
            Log.d(TAG, "Mic -> RMS: ${rms.toInt()} | ZCR: ${String.format("%.3f", zcr)} | Silence: $consecutiveSilenceFrames | Stress: $consecutiveStressFrames")
        }

        if (stressSignal) registerDistressEvent("VOCAL_STRESS_SPIKE")
        if (silenceSignal) registerDistressEvent("ABNORMAL_SILENCE_FREEZE")
    }

    private fun registerDistressEvent(event: String) {
        if (sosTriggered) return
        if (!observedEvents.add(event)) return

        if (observedEvents.size >= 2) {
            sosTriggered = true
            dispatchDistressAlert(score = 1.0, signals = observedEvents.toList())
            stopMonitoring()
        }
    }

    private fun dispatchDistressAlert(score: Double, signals: List<String>) {
        Log.w(TAG, "EMERGENCY DETECTED! Composite Score: $score | Triggers: $signals")

        val prefs = applicationContext.getSharedPreferences("VoicePulsePrefs", Context.MODE_PRIVATE)
        val deviceUuid = prefs.getString("device_uuid", "UNKNOWN_DEVICE") ?: "UNKNOWN_DEVICE"
        val alertMessage = "SOS: Distress detected by VoicePulse. Triggers: ${signals.joinToString(", ")}"

        thread {

            val phoneNumbersToSend = mutableListOf<String>()

            // 1. Fetch saved contacts directly from ContactController (/api/contacts)
            try {
                val contactsEndpoint = URL("https://voice-pulse-backend.onrender.com/api/contacts")
                val conn = contactsEndpoint.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                if (conn.responseCode in 200..299) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val contactsArray = JSONArray(responseText)

                    for (i in 0 until contactsArray.length()) {
                        val obj = contactsArray.getJSONObject(i)

                        // Contact entity uses .getPhone(), with fallback to phoneNumber
                        var phone = obj.optString("phone", "").trim()
                        if (phone.isEmpty()) {
                            phone = obj.optString("phoneNumber", "").trim()
                        }

                        val isAlertEnabled = obj.optBoolean("emergencyAlerts", true)

                        if (phone.isNotEmpty() && !phone.equals("null", ignoreCase = true) && isAlertEnabled) {
                            if (!phoneNumbersToSend.contains(phone)) {
                                phoneNumbersToSend.add(phone)
                            }
                        }
                    }
                    Log.d(TAG, "Fetched ${phoneNumbersToSend.size} contact(s) from database: $phoneNumbersToSend")
                } else {
                    Log.e(TAG, "Failed to fetch contacts, HTTP code: ${conn.responseCode}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching database contacts: ${e.message}")
            }

            // 2. Dispatch SMS ONLY if database numbers exist
            if (phoneNumbersToSend.isNotEmpty()) {
                try {
                    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        applicationContext.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }

                    for (phone in phoneNumbersToSend) {
                        try {
                            smsManager.sendTextMessage(phone, null, alertMessage, null, null)
                            Log.d(TAG, "Emergency SMS dispatched to: $phone")
                        } catch (smsEx: Exception) {
                            Log.e(TAG, "Failed sending SMS to $phone: ${smsEx.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SMS Manager error: ${e.message}")
                }
            } else {
                Log.w(TAG, "No contacts found in database. Skipping SMS transmission.")
            }

            // 3. Dispatch alert record to Spring Boot backend
            try {
                val url = URL(serverUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val payload = JSONObject().apply {
                    put("deviceUuid", deviceUuid)
                    put("timestamp", System.currentTimeMillis())
                    put("score", score)
                    put("signals", JSONArray(signals))
                }

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                Log.d(TAG, "Server alert ingestion code: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to submit HTTP alert: ${e.message}")
            }
        }
    }

    private fun stopMonitoring() {
        isRecording = false
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            voskKeywordDetector?.close()
        } catch (_: Exception) {}
        voskKeywordDetector = null
    }

    private fun unregisterCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                telephonyManager?.unregisterTelephonyCallback(it)
            }
        } else {
            legacyPhoneStateListener?.let {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopMonitoring() }
    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        unregisterCallStateListener()
    }
}