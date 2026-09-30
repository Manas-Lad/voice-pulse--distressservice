package com.semicolons.distressservice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

class VoskKeywordDetector(
    private val context: Context
) {

    companion object {
        private const val TAG = "VoskKeywordDetector"
        private const val SAMPLE_RATE = 16000.0f
        private const val MODEL_ASSET_FOLDER = "vosk-model"
        private const val MODEL_DIRECTORY_NAME = "vosk-model"

        private val DEFAULT_CODE_WORDS = listOf(
            "help",
            "emergency",
            "save me",
            "police",
            "danger",
            "pulse"
        )
    }

    private val activeCodeWords = CopyOnWriteArrayList<String>(DEFAULT_CODE_WORDS)
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var lastRecognizedText: String = ""
    private var initialized = false

    // Reusable byte array to prevent garbage collector thrashing in audio loops
    private var reusableByteChunk = ByteArray(4096)

    fun initialize(): Boolean {
        if (initialized) {
            return true
        }

        return try {
            Log.i(TAG, "Initializing Vosk...")

            val modelDirectory = File(context.filesDir, MODEL_DIRECTORY_NAME)

            if (!modelDirectory.exists()) {
                Log.i(TAG, "Vosk model not found in internal storage. Extracting from assets...")
                copyAssetFolder(MODEL_ASSET_FOLDER, modelDirectory)
                Log.i(TAG, "Vosk model extracted successfully.")
            } else {
                Log.i(TAG, "Vosk model verified in internal storage.")
            }

            Log.i(TAG, "Loading Vosk Model into memory...")
            model = Model(modelDirectory.absolutePath)

            Log.i(TAG, "Constructing Vosk Recognizer...")
            recognizer = Recognizer(model, SAMPLE_RATE)

            initialized = true
            Log.i(TAG, "Vosk engine ready. Active codewords: $activeCodeWords")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Vosk engine: ${e.message}", e)
            close()
            false
        }
    }

    fun updateCustomCodewords(newWords: List<String>) {
        val sanitized = newWords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        activeCodeWords.clear()
        activeCodeWords.addAll(DEFAULT_CODE_WORDS)
        activeCodeWords.addAll(sanitized)
        Log.i(TAG, "Updated active codewords: $activeCodeWords")
    }

    data class DetectionResult(
        val triggered: Boolean,
        val matchedWord: String = "",
        val fullSentence: String = ""
    )

    fun processAudio(audioBuffer: ShortArray, length: Int): DetectionResult {
        if (!initialized || length <= 0) {
            return DetectionResult(triggered = false)
        }

        return try {
            val recognizerInstance = recognizer ?: return DetectionResult(triggered = false)
            val byteCount = length * 2

            if (reusableByteChunk.size < byteCount) {
                reusableByteChunk = ByteArray(byteCount)
            }

            for (i in 0 until length) {
                val sample = audioBuffer[i].toInt()
                reusableByteChunk[i * 2] = (sample and 0xFF).toByte()
                reusableByteChunk[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }

            val accepted = recognizerInstance.acceptWaveForm(reusableByteChunk, byteCount)

            if (accepted) {
                val resultJson = recognizerInstance.result
                return processRecognitionResult(resultJson)
            } else {
                // Read partial result non-blockingly
                try {
                    val partialJson = JSONObject(recognizerInstance.partialResult)
                    val partial = partialJson.optString("partial", "").lowercase().trim()
                    if (partial.isNotEmpty()) {
                        lastRecognizedText = partial
                    }
                } catch (_: Exception) {}
            }

            DetectionResult(triggered = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating audio buffer in Vosk: ${e.message}", e)
            DetectionResult(triggered = false)
        }
    }

    private fun processRecognitionResult(resultJson: String): DetectionResult {
        return try {
            val json = JSONObject(resultJson)
            val recognizedText = json.optString("text", "").lowercase().trim()

            lastRecognizedText = recognizedText
            if (recognizedText.isEmpty()) {
                return DetectionResult(triggered = false)
            }

            Log.i(TAG, "Vosk recognized hypothesis: \"$recognizedText\"")

            for (codeWord in activeCodeWords) {
                if (recognizedText.contains(codeWord)) {
                    Log.w(TAG, "Codeword candidate detected: \"$codeWord\" in sentence: \"$recognizedText\"")
                    return DetectionResult(
                        triggered = true,
                        matchedWord = codeWord,
                        fullSentence = recognizedText
                    )
                }
            }

            DetectionResult(triggered = false, fullSentence = recognizedText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Vosk recognition JSON: ${e.message}", e)
            DetectionResult(triggered = false)
        }
    }

    private fun copyAssetFolder(assetPath: String, destination: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetPath)
            ?: throw IllegalStateException("Unable to read asset folder: $assetPath")

        if (!destination.exists()) {
            destination.mkdirs()
        }

        for (fileName in files) {
            val sourcePath = "$assetPath/$fileName"
            val destinationFile = File(destination, fileName)
            val children = assetManager.list(sourcePath)

            if (children != null && children.isNotEmpty()) {
                copyAssetFolder(sourcePath, destinationFile)
            } else {
                assetManager.open(sourcePath).use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }
        }
    }

    fun getLatestHypothesis(): String = lastRecognizedText

    fun close() {
        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Vosk recognizer: ${e.message}")
        }
        try {
            model?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Vosk model: ${e.message}")
        }

        recognizer = null
        model = null
        initialized = false
        lastRecognizedText = ""
        Log.i(TAG, "Vosk Keyword Detector successfully released.")
    }
}