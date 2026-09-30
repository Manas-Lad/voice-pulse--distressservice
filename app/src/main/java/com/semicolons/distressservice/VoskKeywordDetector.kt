package com.semicolons.distressservice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException

class VoskKeywordDetector(private val context: Context) {

    companion object {
        private const val TAG = "VoskKeywordDetector"
        private const val SAMPLE_RATE = 16000.0f
    }

    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private val activeCustomCodewords = mutableSetOf<String>()
    private var lastLoggedPartial = ""

    data class DetectionResult(
        val triggered: Boolean,
        val matchedWord: String,
        val fullSentence: String
    )

    fun initialize(onInitialized: () -> Unit) {
        // Pointing directly to your asset folder "vosk-model"
        StorageService.unpack(
            context,
            "model-en-us",
            "model",
            { model: Model ->
                voskModel = model
                setupRecognizer()
                Log.i(TAG, "Vosk model unpacked and initialized successfully.")
                onInitialized()
            },
            { exception: IOException ->
                Log.e(TAG, "Failed to unpack Vosk model: ${exception.message}", exception)
            }
        )
    }

    private fun setupRecognizer() {
        voskModel?.let { model ->
            recognizer = Recognizer(model, SAMPLE_RATE)
        }
    }

    fun updateCustomCodewords(newWords: List<String>) {
        synchronized(activeCustomCodewords) {
            activeCustomCodewords.clear()
            activeCustomCodewords.addAll(
                newWords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            )
            Log.i(TAG, "Active custom codewords updated strictly from configuration: $activeCustomCodewords")
        }
    }

    fun processAudio(buffer: ShortArray, readSamples: Int): DetectionResult {
        val rec = recognizer ?: return DetectionResult(false, "", "")

        val isFinal = rec.acceptWaveForm(buffer, readSamples)
        val jsonString = if (isFinal) rec.result else rec.partialResult
        val textKey = if (isFinal) "text" else "partial"

        val parsedText = try {
            JSONObject(jsonString).optString(textKey, "").lowercase().trim()
        } catch (e: Exception) {
            ""
        }

        if (parsedText.isNotEmpty()) {
            if (isFinal) {
                Log.i(TAG, "🎤 [Vosk Final Utterance] ---> \"$parsedText\"")
                lastLoggedPartial = ""
            } else if (parsedText != lastLoggedPartial) {
                Log.d(TAG, "🗣️️  [Vosk Live Stream]   ---> \"$parsedText\"")
                lastLoggedPartial = parsedText
            }

            synchronized(activeCustomCodewords) {
                for (word in activeCustomCodewords) {
                    val regex = Regex("\\b${Regex.escape(word)}\\b")
                    if (regex.containsMatchIn(parsedText)) {
                        Log.w(TAG, "🚨 [Codeword Spotted!] -> \"$word\" in speech: \"$parsedText\"")
                        if (!isFinal) rec.reset()
                        return DetectionResult(
                            triggered = true,
                            matchedWord = word,
                            fullSentence = parsedText
                        )
                    }
                }
            }

            if (isFinal) {
                return DetectionResult(false, "", parsedText)
            }
        }
        return DetectionResult(false, "", "")
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}