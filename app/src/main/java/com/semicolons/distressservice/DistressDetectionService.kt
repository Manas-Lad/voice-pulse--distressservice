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

    // -----------------------------
    // Voice stress detection
    // -----------------------------

    private var consecutiveStressFrames = 0
    private val stressThresholdFrames = 15

    // -----------------------------
    // Silence detection
    // -----------------------------

    private var consecutiveSilenceFrames = 0
    private val silenceThresholdFrames = 300

    private var logThrottle = 0

    // -----------------------------
    // Emergency event engine
    // -----------------------------

    private val observedEvents = mutableSetOf<String>()

    private var sosTriggered = false

    // -----------------------------
    // Vosk
    // -----------------------------

    private var voskKeywordDetector: VoskKeywordDetector? = null

    // -----------------------------
    // Emergency configuration
    // -----------------------------

    private val emergencyPhone = "+919819933448"

    private val serverUrl =
        "https://voice-pulse-backend.onrender.com/api/alerts"

    // -----------------------------
    // Telephony
    // -----------------------------

    private var telephonyManager: TelephonyManager? = null

    private var telephonyCallback: Any? = null

    private var legacyPhoneStateListener: PhoneStateListener? = null

    // ============================================================
    // SERVICE
    // ============================================================

    override fun onServiceConnected() {

        super.onServiceConnected()

        Log.d(
            TAG,
            "Distress Safety Service Connected & Standing By."
        )

        registerCallStateListener()
    }

    // ============================================================
    // CALL STATE
    // ============================================================

    private fun registerCallStateListener() {

        telephonyManager =
            getSystemService(Context.TELEPHONY_SERVICE)
                    as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            val callback =
                object :
                    TelephonyCallback(),
                    TelephonyCallback.CallStateListener {

                    override fun onCallStateChanged(
                        state: Int
                    ) {
                        handleCallState(state)
                    }
                }

            telephonyCallback = callback

            telephonyManager?.registerTelephonyCallback(
                mainExecutor,
                callback
            )

        } else {

            @Suppress("DEPRECATION")
            val listener =
                object : PhoneStateListener() {

                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(
                        state: Int,
                        phoneNumber: String?
                    ) {
                        handleCallState(state)
                    }
                }

            legacyPhoneStateListener = listener

            @Suppress("DEPRECATION")
            telephonyManager?.listen(
                listener,
                PhoneStateListener.LISTEN_CALL_STATE
            )
        }
    }

    private fun handleCallState(state: Int) {

        when (state) {

            TelephonyManager.CALL_STATE_OFFHOOK -> {

                Log.d(
                    TAG,
                    "Call Connected (OFFHOOK)! Starting microphone capture loop."
                )

                startMonitoring()
            }

            TelephonyManager.CALL_STATE_IDLE -> {

                Log.d(
                    TAG,
                    "Call Ended (IDLE). Stopping Monitoring."
                )

                stopMonitoring()
            }
        }
    }

    // ============================================================
    // START MONITORING
    // ============================================================

    @android.annotation.SuppressLint("MissingPermission")
    private fun startMonitoring() {

        if (isRecording) {
            return
        }

        isRecording = true

        // Reset everything for the new call.
        consecutiveSilenceFrames = 0
        consecutiveStressFrames = 0
        logThrottle = 0

        observedEvents.clear()
        sosTriggered = false

        Log.d(
            TAG,
            "========================================"
        )

        Log.d(
            TAG,
            "NEW CALL MONITORING SESSION"
        )

        Log.d(
            TAG,
            "Event state reset."
        )

        Log.d(
            TAG,
            "========================================"
        )

        val minBufSize =
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        val bufferCapacity =
            maxOf(
                minBufSize,
                frameSize * 2
            )

        try {

            val audioManager =
                getSystemService(
                    Context.AUDIO_SERVICE
                ) as android.media.AudioManager

            audioManager.mode =
                android.media.AudioManager.MODE_IN_COMMUNICATION

            audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferCapacity
                )

            audioRecord?.startRecording()

            Log.d(
                TAG,
                "AudioRecord started successfully."
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "AudioRecord permission denied: ${e.message}"
            )

            isRecording = false

            return

        } catch (e: Exception) {

            Log.e(
                TAG,
                "AudioRecord initialization failed: ${e.message}"
            )

            isRecording = false

            return
        }

        thread {

            // Existing startup delay.
            Thread.sleep(9000)

            // -----------------------------------------
            // Initialize Vosk
            // -----------------------------------------

            try {

                Log.d(
                    TAG,
                    "Initializing Vosk keyword detector..."
                )

                voskKeywordDetector =
                    VoskKeywordDetector(applicationContext)

                val initialized =
                    voskKeywordDetector?.initialize() == true

                if (initialized) {

                    Log.d(
                        TAG,
                        "Vosk keyword detector ready."
                    )

                } else {

                    Log.e(
                        TAG,
                        "Vosk initialization failed."
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Vosk initialization exception",
                    e
                )
            }

            // -----------------------------------------
            // Audio processing loop
            // -----------------------------------------

            val audioBuffer =
                ShortArray(frameSize)

            while (isRecording) {

                val read =
                    audioRecord?.read(
                        audioBuffer,
                        0,
                        frameSize
                    ) ?: 0

                if (read > 0) {

                    processAudioChunk(
                        audioBuffer,
                        read
                    )

                } else {

                    Log.w(
                        TAG,
                        "AudioRecord read error / empty: status code $read"
                    )

                    Thread.sleep(100)
                }
            }
        }
    }

    // ============================================================
    // AUDIO PROCESSING
    // ============================================================

    private fun processAudioChunk(
        buffer: ShortArray,
        readSize: Int
    ) {

        if (sosTriggered) {
            return
        }

        // ========================================================
        // VOSK CODE-WORD DETECTION
        // ========================================================

        val codeWordDetected =
            voskKeywordDetector?.processAudio(
                buffer,
                readSize
            ) == true

        if (codeWordDetected) {

            Log.w(
                TAG,
                "========================================"
            )

            Log.w(
                TAG,
                "CODE WORD DETECTED"
            )

            Log.w(
                TAG,
                "CODE WORD → IMMEDIATE SOS"
            )

            val finalSignals =
                (observedEvents + "CODE_WORD").toList()

            Log.w(
                TAG,
                "Final SOS event history: $finalSignals"
            )

            Log.w(
                TAG,
                "========================================"
            )

            sosTriggered = true

            dispatchDistressAlert(
                score = 1.0,
                signals = finalSignals
            )

            stopMonitoring()

            return
        }

        // ========================================================
        // RMS
        // ========================================================

        var sumSquares = 0.0

        for (i in 0 until readSize) {

            sumSquares +=
                buffer[i] * buffer[i]
        }

        val rms =
            sqrt(
                sumSquares / readSize
            )

        // ========================================================
        // ZERO CROSSING RATE
        // ========================================================

        var zeroCrossings = 0

        for (i in 1 until readSize) {

            if (
                (buffer[i] >= 0 &&
                        buffer[i - 1] < 0) ||

                (buffer[i] < 0 &&
                        buffer[i - 1] >= 0)
            ) {
                zeroCrossings++
            }
        }

        val zcr =
            zeroCrossings.toDouble() / readSize

        // ========================================================
        // SILENCE DETECTION
        // ========================================================

        var silenceSignal = false

        if (rms < 200.0) {

            consecutiveSilenceFrames++

            if (
                consecutiveSilenceFrames >=
                silenceThresholdFrames
            ) {

                silenceSignal = true
            }

        } else {

            consecutiveSilenceFrames = 0
        }

        // ========================================================
        // STRESS DETECTION
        // ========================================================

        if (
            rms > 5000.0 &&
            zcr > 0.05
        ) {

            consecutiveStressFrames++

        } else {

            consecutiveStressFrames =
                maxOf(
                    0,
                    consecutiveStressFrames - 1
                )
        }

        val stressSignal =
            consecutiveStressFrames >=
                    stressThresholdFrames

        // ========================================================
        // DEBUG LOG
        // ========================================================

        logThrottle++

        if (logThrottle % 10 == 0) {

            Log.d(
                TAG,
                "Mic -> RMS: ${rms.toInt()} | " +
                        "ZCR: ${String.format("%.3f", zcr)} | " +
                        "Silence: $consecutiveSilenceFrames/$silenceThresholdFrames | " +
                        "Stress: $consecutiveStressFrames/$stressThresholdFrames"
            )
        }

        // ========================================================
        // EVENT ENGINE
        // ========================================================

        if (stressSignal) {

            registerDistressEvent(
                "VOCAL_STRESS_SPIKE"
            )
        }

        if (silenceSignal) {

            registerDistressEvent(
                "ABNORMAL_SILENCE_FREEZE"
            )
        }
    }

    // ============================================================
    // EVENT ENGINE
    // ============================================================

    private fun registerDistressEvent(
        event: String
    ) {

        if (sosTriggered) {
            return
        }

        // Set guarantees that repeated occurrences
        // of the same event do NOT count multiple times.

        val wasNew =
            observedEvents.add(event)

        if (!wasNew) {

            return
        }

        Log.w(
            TAG,
            "========================================"
        )

        Log.w(
            TAG,
            "DISTRESS EVENT DETECTED: $event"
        )

        Log.w(
            TAG,
            "Distinct events observed: $observedEvents"
        )

        Log.w(
            TAG,
            "Event count: ${observedEvents.size}"
        )

        Log.w(
            TAG,
            "========================================"
        )

        // --------------------------------------------------------
        // No code word:
        // Require TWO DISTINCT event types.
        // --------------------------------------------------------

        if (observedEvents.size >= 2) {

            sosTriggered = true

            Log.w(
                TAG,
                "========================================"
            )

            Log.w(
                TAG,
                "2 DISTINCT DISTRESS EVENTS DETECTED"
            )

            Log.w(
                TAG,
                "TRIGGERING SOS"
            )

            Log.w(
                TAG,
                "Events = $observedEvents"
            )

            Log.w(
                TAG,
                "========================================"
            )

            dispatchDistressAlert(
                score = 1.0,
                signals = observedEvents.toList()
            )

            stopMonitoring()
        }
    }

    // ============================================================
    // DISPATCH SOS
    // ============================================================

    private fun dispatchDistressAlert(
        score: Double,
        signals: List<String>
    ) {

        Log.w(
            TAG,
            "EMERGENCY DETECTED!"
        )

        Log.w(
            TAG,
            "Composite Score: $score"
        )

        Log.w(
            TAG,
            "Triggers: $signals"
        )

        // --------------------------------------------------------
        // SMS
        // --------------------------------------------------------

        try {

            val smsManager =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                    applicationContext.getSystemService(
                        SmsManager::class.java
                    )

                } else {

                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

            val alertMessage =
                "SOS: Distress detected. " +
                        "Triggers: ${signals.joinToString(", ")}"

            smsManager.sendTextMessage(
                emergencyPhone,
                null,
                alertMessage,
                null,
                null
            )

            Log.d(
                TAG,
                "Emergency SMS dispatched directly via cellular baseband."
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to send SMS: ${e.message}"
            )
        }

        // --------------------------------------------------------
        // Backend
        // --------------------------------------------------------

        thread {

            try {

                val url =
                    URL(serverUrl)

                val conn =
                    url.openConnection()
                            as HttpURLConnection

                conn.requestMethod = "POST"

                conn.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                conn.doOutput = true

                conn.connectTimeout = 3000

                //  NEW (Bound to this device's registered UUID)
                val prefs = applicationContext.getSharedPreferences("VoicePulsePrefs", Context.MODE_PRIVATE)
                val deviceUuid = prefs.getString("device_uuid", "UNKNOWN_DEVICE")

                val payload =
                    JSONObject().apply {

                        put(
                            "deviceUuid",
                            deviceUuid
                        )

                        put(
                            "timestamp",
                            System.currentTimeMillis()
                        )

                        put(
                            "score",
                            score
                        )

                        put(
                            "signals",
                            JSONArray(signals)
                        )
                    }

                OutputStreamWriter(
                    conn.outputStream
                ).use { writer ->

                    writer.write(
                        payload.toString()
                    )

                    writer.flush()
                }

                val responseCode =
                    conn.responseCode

                Log.d(
                    TAG,
                    "Spring Boot Server Response: $responseCode"
                )

                conn.disconnect()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to send HTTP alert: ${e.message}"
                )
            }
        }

        // Inside dispatchDistressAlert(score: Double, signals: List<String>):
        thread {
            try {
                val prefs = applicationContext.getSharedPreferences("VoicePulsePrefs", Context.MODE_PRIVATE)
                val deviceUuid = prefs.getString("device_uuid", "UNKNOWN_DEVICE")

                val url = URL(serverUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 3000

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
                Log.d(TAG, "Spring Boot Server Response: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send HTTP alert: ${e.message}")
            }
        }
    }

    // ============================================================
    // STOP MONITORING
    // ============================================================

    private fun stopMonitoring() {

        isRecording = false

        try {

            val audioManager =
                getSystemService(
                    Context.AUDIO_SERVICE
                ) as android.media.AudioManager

            audioManager.mode =
                android.media.AudioManager.MODE_NORMAL

            audioRecord?.stop()

            audioRecord?.release()

        } catch (_: Exception) {
        }

        audioRecord = null

        try {

            voskKeywordDetector?.close()

        } catch (_: Exception) {
        }

        voskKeywordDetector = null

        Log.d(
            TAG,
            "Monitoring stopped and Vosk resources released."
        )
    }

    // ============================================================
    // TELEPHONY CLEANUP
    // ============================================================

    private fun unregisterCallStateListener() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            (telephonyCallback as? TelephonyCallback)?.let {

                telephonyManager
                    ?.unregisterTelephonyCallback(it)
            }

        } else {

            legacyPhoneStateListener?.let {

                @Suppress("DEPRECATION")

                telephonyManager?.listen(
                    it,
                    PhoneStateListener.LISTEN_NONE
                )
            }
        }
    }

    // ============================================================
    // ACCESSIBILITY
    // ============================================================

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
    }

    override fun onInterrupt() {

        stopMonitoring()
    }

    override fun onDestroy() {

        super.onDestroy()

        stopMonitoring()

        unregisterCallStateListener()
    }
}