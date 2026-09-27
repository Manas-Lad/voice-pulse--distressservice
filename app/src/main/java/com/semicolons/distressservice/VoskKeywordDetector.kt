package com.semicolons.distressservice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

class VoskKeywordDetector(
    private val context: Context
) {

    companion object {
        private const val TAG = "VoskKeywordDetector"
        private const val SAMPLE_RATE = 16000.0f

        private const val MODEL_ASSET_FOLDER = "vosk-model"
        private const val MODEL_DIRECTORY_NAME = "vosk-model"

        private val CODE_WORDS = listOf(
            "help",
            "emergency",
            "save me",
            "police",
            "danger",
            "pulse"
        )
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    private var initialized = false

    /**
     * Loads the Vosk model from:
     *
     * app/src/main/assets/vosk-model/
     *
     * Vosk cannot directly use the compressed APK assets as a Model,
     * so the model is copied into the app's internal storage first.
     */
    fun initialize(): Boolean {

        if (initialized) {
            return true
        }

        return try {

            Log.i(TAG, "Initializing Vosk...")

            val modelDirectory = File(
                context.filesDir,
                MODEL_DIRECTORY_NAME
            )

            if (!modelDirectory.exists()) {
                Log.i(TAG, "Vosk model not found in internal storage.")
                Log.i(TAG, "Copying model from assets...")

                copyAssetFolder(
                    MODEL_ASSET_FOLDER,
                    modelDirectory
                )

                Log.i(TAG, "Vosk model copied successfully.")
            } else {
                Log.i(TAG, "Vosk model already exists in internal storage.")
            }

            Log.i(TAG, "Loading Vosk Model...")

            model = Model(modelDirectory.absolutePath)

            Log.i(TAG, "Creating Vosk Recognizer...")

            recognizer = Recognizer(
                model,
                SAMPLE_RATE
            )

            initialized = true

            Log.i(TAG, "========================================")
            Log.i(TAG, "VOSK INITIALIZED SUCCESSFULLY")
            Log.i(TAG, "Sample rate = $SAMPLE_RATE Hz")
            Log.i(TAG, "Code words = $CODE_WORDS")
            Log.i(TAG, "========================================")

            true

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to initialize Vosk",
                e
            )

            close()

            false
        }
    }

    /**
     * Feed PCM audio from the existing AudioRecord stream.
     *
     * The audio must be:
     * - 16 kHz
     * - Mono
     * - PCM 16-bit
     */
    fun processAudio(
        audioBuffer: ShortArray,
        length: Int
    ): Boolean {

        if (!initialized) {
            Log.w(
                TAG,
                "processAudio() called before Vosk initialization."
            )
            return false
        }

        if (length <= 0) {
            return false
        }

        return try {

            val recognizerInstance = recognizer ?: return false

            val audioBytes = ByteArray(length * 2)

            for (i in 0 until length) {

                val sample = audioBuffer[i].toInt()

                audioBytes[i * 2] =
                    (sample and 0xFF).toByte()

                audioBytes[i * 2 + 1] =
                    ((sample shr 8) and 0xFF).toByte()
            }

            val accepted = recognizerInstance.acceptWaveForm(
                audioBytes,
                audioBytes.size
            )

            if (accepted) {
                val resultJson = recognizerInstance.result
                return processRecognitionResult(resultJson)
            }

            return false

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error processing Vosk audio",
                e
            )

            false
        }
    }

    /**
     * Checks recognized speech for configured code words.
     */
    private fun processRecognitionResult(
        resultJson: String
    ): Boolean {

        return try {

            Log.d(
                TAG,
                "Vosk result = $resultJson"
            )

            val json = JSONObject(resultJson)

            val recognizedText =
                json.optString("text", "")
                    .lowercase()
                    .trim()

            if (recognizedText.isEmpty()) {
                return false
            }

            Log.i(
                TAG,
                "Recognized text: \"$recognizedText\""
            )

            for (codeWord in CODE_WORDS) {

                if (recognizedText.contains(codeWord)) {

                    Log.w(
                        TAG,
                        "========================================"
                    )

                    Log.w(
                        TAG,
                        "CODE WORD DETECTED: \"$codeWord\""
                    )

                    Log.w(
                        TAG,
                        "Recognized text: \"$recognizedText\""
                    )

                    Log.w(
                        TAG,
                        "========================================"
                    )

                    return true
                }
            }

            false

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to process Vosk result",
                e
            )

            false
        }
    }

    /**
     * Recursively copies a directory from assets
     * into internal app storage.
     */
    private fun copyAssetFolder(
        assetPath: String,
        destination: File
    ) {

        val assetManager = context.assets

        val files = assetManager.list(assetPath)
            ?: throw IllegalStateException(
                "Unable to read asset folder: $assetPath"
            )

        if (!destination.exists()) {
            destination.mkdirs()
        }

        for (fileName in files) {

            val sourcePath =
                "$assetPath/$fileName"

            val destinationFile =
                File(destination, fileName)

            val children =
                assetManager.list(sourcePath)

            if (children != null && children.isNotEmpty()) {

                copyAssetFolder(
                    sourcePath,
                    destinationFile
                )

            } else {

                assetManager.open(sourcePath).use { input ->

                    FileOutputStream(
                        destinationFile
                    ).use { output ->

                        val buffer = ByteArray(8192)

                        while (true) {

                            val bytesRead =
                                input.read(buffer)

                            if (bytesRead == -1) {
                                break
                            }

                            output.write(
                                buffer,
                                0,
                                bytesRead
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Releases Vosk resources.
     */
    fun close() {

        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing recognizer", e)
        }

        try {
            model?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing model", e)
        }

        recognizer = null
        model = null
        initialized = false

        Log.i(TAG, "Vosk resources released.")
    }
}