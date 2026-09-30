package com.semicolons.distressservice

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DistressDetectionService : AccessibilityService() {

    companion object {
        private const val TAG = "DistressService"
        private const val SAMPLE_RATE = 16000
        private const val SILENCE_RMS_THRESHOLD = 80.0
        private const val PROLONGED_SILENCE_WARNING_MS = 15000.0
        private const val BACKEND_URL = "https://voice-pulse-backend.onrender.com"
        private const val FRONTEND_DASHBOARD_URL = "https://voice-pulse-frontend.vercel.app/"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: PhoneStateListener
    private lateinit var voskDetector: VoskKeywordDetector
    private lateinit var contextEngine: ContextVerificationEngine
    private lateinit var audioEnhanceManager: AudioEnhancementManager
    private lateinit var prefs: SharedPreferences

    private var consecutiveSilenceFrames = 0
    private var hasTriggeredAlert = false // Guard to ensure single execution
    private val pendingWarnings = mutableListOf<String>()
    private var hasLoggedSilenceWarningForBlock = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "DistressDetectionService connected.")

        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        audioEnhanceManager = AudioEnhancementManager()
        contextEngine = ContextVerificationEngine(this)
        voskDetector = VoskKeywordDetector(this)

        voskDetector.initialize {
            Log.i(TAG, "Vosk initialized. Fetching configured trigger words...")
            fetchCustomSecretCodes()
        }

        registerCallListener()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "DistressDetectionService interrupted.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_START_TEST_LISTENER") {
            Log.i(TAG, "Manual test trigger initiated. Starting capture pipeline...")
            startAudioCapture()
        } else if (intent?.action == "ACTION_STOP_TEST_LISTENER") {
            Log.i(TAG, "Manual test stop initiated. Stopping capture pipeline...")
            stopAudioCapture()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun registerCallListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                if (hasTriggeredAlert) return

                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.i(TAG, "Call offhook. Initializing audio capture pipeline...")
                        fetchCustomSecretCodes()
                        startAudioCapture()
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.i(TAG, "Call idle. Stopping audio capture pipeline...")
                        stopAudioCapture()
                    }
                }
            }
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun getOrCreateDeviceUuid(): String {
        var uuidString = prefs.getString(MainActivity.KEY_DEVICE_UUID, null)
        if (uuidString.isNullOrEmpty()) {
            uuidString = UUID.randomUUID().toString()
            prefs.edit().putString(MainActivity.KEY_DEVICE_UUID, uuidString).apply()
        }
        return uuidString
    }

    fun fetchCustomSecretCodes() {
        thread(start = true, name = "FetchCustomKeywordsWorker") {
            val deviceUuid = getOrCreateDeviceUuid()
            var connection: HttpURLConnection? = null

            try {
                val regUrl = URL("$BACKEND_URL/api/users/register-device")
                val regConn = (regUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 60000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                val regPayload = JSONObject().apply {
                    put("deviceUuid", deviceUuid)
                }
                OutputStreamWriter(regConn.outputStream).use { it.write(regPayload.toString()) }
                regConn.responseCode
                regConn.disconnect()

                val url = URL("$BACKEND_URL/api/users/device/$deviceUuid/codes")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 60000
                    readTimeout = 60000
                }

                val responseCode = connection.responseCode
                val customWords = mutableListOf<String>()

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (response.startsWith("[")) {
                        val jsonArray = JSONArray(response)
                        for (i in 0 until jsonArray.length()) {
                            val word = jsonArray.optString(i, "").trim().lowercase()
                            if (word.isNotEmpty()) customWords.add(word)
                        }
                    }
                }

                if (customWords.isNotEmpty()) {
                    voskDetector.updateCustomCodewords(customWords)
                    Log.i(TAG, "Armed detection engine with dynamic keywords: $customWords")
                } else {
                    voskDetector.updateCustomCodewords(listOf("orange", "pulse"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching keywords: ${e.message}. Using fallback.", e)
                voskDetector.updateCustomCodewords(listOf("orange", "pulse"))
            } finally {
                connection?.disconnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startAudioCapture() {
        if (isRecording || hasTriggeredAlert) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufferSize.coerceAtLeast(SAMPLE_RATE * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Unable to initialize AudioRecord instance.")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            consecutiveSilenceFrames = 0

            recordingThread = thread(start = true, name = "AudioProcessingWorker") {
                processAudioStream(bufferSize)
            }

            Log.i(TAG, "Audio capture pipeline active.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio capture: ${e.message}", e)
        }
    }

    private fun processAudioStream(bufferSize: Int) {
        val audioBuffer = ShortArray(bufferSize / 2)

        // DSP & Silence State Trackers
        var consecutiveSilentFrames = 0
        val silenceThresholdRms = 150.0 // Tune based on your ambient background noise floor
        val sampleDurationMs = (bufferSize.toDouble() / SAMPLE_RATE) * 1000.0

        Thread.sleep(9000)

        while (isRecording && !hasTriggeredAlert && !Thread.currentThread().isInterrupted) {
            val record = audioRecord ?: break
            val readSamples = record.read(audioBuffer, 0, audioBuffer.size)

            if (readSamples > 0) {
                // 1. Calculate Real-Time DSP Metrics
                val rms = calculateRms(audioBuffer, readSamples)
                val zcr = calculateZcr(audioBuffer, readSamples)
                val isSilent = rms < silenceThresholdRms

                if (isSilent) {
                    consecutiveSilentFrames++
                    val currentSilenceMs = consecutiveSilentFrames * sampleDurationMs

                    // Check if silence has crossed the 15-second warning threshold
                    if (currentSilenceMs >= PROLONGED_SILENCE_WARNING_MS && !hasLoggedSilenceWarningForBlock) {
                        val warningMsg = "Prolonged silence/hesitation detected (${currentSilenceMs.toInt()}ms)"
                        synchronized(pendingWarnings) {
                            pendingWarnings.add(warningMsg)
                        }
                        Log.w(TAG, "⚠️ Warning recorded and buffered: $warningMsg")
                        hasLoggedSilenceWarningForBlock = true // Prevent duplicate warnings for the same block
                    }
                } else {
                    hasLoggedSilenceWarningForBlock = false // Reset block flag when speech resumes

                    if (consecutiveSilenceFrames > 0) {
                        val silenceDurationMs = consecutiveSilenceFrames * sampleDurationMs
                        Log.i(TAG, "Silence block ended. Duration: ${silenceDurationMs.toInt()}ms (Frames: $consecutiveSilentFrames)")
                        contextEngine.recordSilenceEvent(silenceDurationMs)
                    }
                    consecutiveSilenceFrames = 0
                }

                Log.w(TAG,"RMS -> $rms : ZCR -> $zcr : Silence -> ")

                // 2. Feed Audio to Vosk Keyword Detector
                val result = voskDetector.processAudio(audioBuffer, readSamples)

                if (result.triggered && !hasTriggeredAlert) {
                    val currentSilenceDuration = consecutiveSilentFrames * sampleDurationMs

                    // Evaluate semantic context alongside current acoustic silence state
                    val isAuthenticDistress = contextEngine.evaluateSentenceWithAcoustics(
                        recognizedSentence = result.fullSentence,
                        matchedCodeword = result.matchedWord,
                        rmsEnergy = rms,
                        precedingSilenceMs = currentSilenceDuration
                    )

                    if (isAuthenticDistress) {
                        hasTriggeredAlert = true
                        Log.e(TAG, "AUTHENTIC DISTRESS CONFIRMED for [${result.matchedWord}] -> RMS: $rms | Preceding Silence: ${currentSilenceDuration}ms")
                        triggerEmergencySequence(result.matchedWord, result.fullSentence)
                        break
                    }
                } else if (result.fullSentence.isNotEmpty()) {
                    contextEngine.recordNormalSentence(result.fullSentence)
                }
            }
        }
    }

    private fun calculateRms(buffer: ShortArray, length: Int): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / length)
    }

    private fun calculateZcr(buffer: ShortArray, length: Int): Double {
        if (length <= 1) return 0.0
        var crossings = 0
        for (i in 0 until length - 1) {
            if ((buffer[i] >= 0 && buffer[i + 1] < 0) || (buffer[i] < 0 && buffer[i + 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toDouble() / (length - 1)
    }

    @Synchronized
    private fun stopAudioCapture() {
        if (!isRecording) return
        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error halting AudioRecord: ${e.message}", e)
        } finally {
            audioRecord = null
            recordingThread?.interrupt()
            recordingThread = null
            contextEngine.clearHistory()
            Log.i(TAG, "Audio capture pipeline stopped.")
        }
    }

    private fun triggerEmergencySequence(codeword: String, transcript: String) {
        thread(start = true, name = "EmergencySequenceWorker") {
            try {
                // 1. Fetch Real-Time High-Accuracy GPS Location (sent to backend only, NOT in SMS)
                val location = fetchRealtimeHighAccuracyLocation()
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0
                val accuracy = location?.accuracy ?: 0.0f
                Log.i(TAG, "Real-time GPS fix acquired -> Lat: $lat, Lon: $lon, Accuracy: ${accuracy}m")

                // 2. Dispatch Backend Alert and capture the returned alertToken / alertId
                val alertToken = dispatchBackendAlert(codeword, transcript, lat, lon, accuracy)
                Log.i(TAG, "Backend alert successfully dispatched. Received alertToken: $alertToken")

                // 3. Dispatch Backend Contacts SMS containing deviceToken and alertToken (No location)
                dispatchEmergencySms(codeword, alertToken)

            } catch (e: Exception) {
                Log.e(TAG, "Error in emergency sequence execution: ${e.message}", e)
            } finally {
                // 4. Terminate Service Cleanly on First Alert (Local device stays completely quiet)
                stopAudioCapture()
                Log.w(TAG,"Audio Capture halted after first alert")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchRealtimeHighAccuracyLocation(): Location? {
        return try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            val task = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            )
            Tasks.await(task, 6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Real-time location timeout/error: ${e.message}. Falling back.")
            getLastKnownLocationFallback()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocationFallback(): Location? {
        return try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            null
        }
    }

    private fun dispatchBackendAlert(codeword: String, transcript: String, lat: Double, lon: Double, accuracy: Float): String? {
        var connection: HttpURLConnection? = null
        try {
            val deviceUuid = getOrCreateDeviceUuid()

            val warningsArray = JSONArray()
            synchronized(pendingWarnings) {
                for (warning in pendingWarnings) {
                    warningsArray.put(warning)
                }
            }

            val payload = JSONObject().apply {
                put("deviceUuid", deviceUuid)
                put("score", 1.0)
                put("signals", JSONArray().apply { put("Codeword: $codeword") })
                put("warnings", warningsArray) // <-- Attached accumulated warnings
                put("transcript", transcript)
                put("timestamp", System.currentTimeMillis())
                put("latitude", lat)
                put("longitude", lon)
                put("locationAccuracy", accuracy.toDouble())
                put("status", "TRIGGERED")
            }

            val url = URL("$BACKEND_URL/api/alerts")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; utf-8")
                setRequestProperty("Accept", "application/json")
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val responseString = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }.trim()
                Log.i(TAG, "Backend raw response: $responseString") // <-- Check what this prints in Logcat!

                if (responseString.startsWith("{")) {
                    val jsonResponse = JSONObject(responseString)
                    return jsonResponse.optString("shareToken", // Matches your backend controller key!
                        jsonResponse.optString("alertToken",
                            jsonResponse.optString("token",
                                jsonResponse.optString("id", ""))))
                } else if (responseString.isNotEmpty()) {
                    return responseString
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch alert to backend: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
        return null
    }

    private fun dispatchEmergencySms(codeword: String, alertToken: String?) {
        val deviceUuid = getOrCreateDeviceUuid()
        var connection: HttpURLConnection? = null
        try {
            try {
                val url = URL("$BACKEND_URL/api/users/device/$deviceUuid/contacts")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                Log.i(TAG,"URL has been successfully opened")
            } catch (e : Exception) {
                Log.w(TAG,"Failed to openConnection: ${e.message}")
            }

            if (connection?.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val contactsArray = JSONArray(response)

                Log.i(TAG,"response is: $response")
                Log.i(TAG,"contactsArray is: $contactsArray")

                val smsManager = getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                Log.i(TAG,"Successfully got SystemService : SmsManager")

                val tokenParam = if (!alertToken.isNullOrEmpty()) "&alertToken=$alertToken" else ""
                val dashboardLink = "${FRONTEND_DASHBOARD_URL}?deviceToken=$deviceUuid$tokenParam"
                Log.i(TAG,"dashboardLink is $dashboardLink")
                val warningsText = synchronized(pendingWarnings) {
                    if (pendingWarnings.isNotEmpty()) " | Warnings: ${pendingWarnings.joinToString("; ")}" else ""
                }
                val message = "EMERGENCY ALERT: Distress codeword '$codeword' detected! $warningsText! View live dashboard: $dashboardLink"

                for (i in 0 until contactsArray.length()) {
                    val contact = contactsArray.getJSONObject(i)
                    val phone = contact.optString("phone", contact.optString("phoneNumber", ""))
                    val isEnabled = contact.optBoolean("emergencyAlerts", true)

                    if (phone.isNotEmpty() && isEnabled) {
                        // Unconditional multipart transmission
                        val parts = smsManager.divideMessage(message)
                        smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
                        Log.i(TAG, "Unconditional multipart emergency SMS dispatched to: $phone (${parts.size} parts)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send emergency SMS: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioCapture()
    }
}