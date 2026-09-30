package com.semicolons.distressservice

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import java.util.LinkedList

class ContextVerificationEngine(private val context: Context) {

    companion object {
        private const val TAG = "ContextEngine"
        private const val MODEL_PATH = "universal_sentence_encoder.tflite"
        private const val SIMILARITY_THRESHOLD = 0.88f
        private const val MAX_CONTEXT_SENTENCES = 3

        // Acoustic Threshold Adjustments
        private const val ABNORMAL_SILENCE_THRESHOLD_MS = 1500.0 // 1.5 seconds of dead air prior to trigger
        private const val SILENCE_SENSITIVITY_BOOST = 0.05f       // Lowers similarity bar if an abnormal pause is detected
    }

    private var textEmbedder: TextEmbedder? = null
    private val conversationHistory = LinkedList<String>()

    // Tracks recent acoustic silence events fed from the DSP loop
    private var lastRecordedSilenceMs: Double = 0.0

    init {
        initializeEmbedder()
    }

    private fun initializeEmbedder() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .build()

            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .setQuantize(false)
                .build()

            textEmbedder = TextEmbedder.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe TextEmbedder initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe TextEmbedder: ${e.message}", e)
        }
    }

    @Synchronized
    fun recordNormalSentence(sentence: String) {
        val clean = sentence.trim()
        if (clean.isNotEmpty() && clean.split("\\s+".toRegex()).size >= 2) {
            if (conversationHistory.size >= MAX_CONTEXT_SENTENCES) {
                conversationHistory.removeFirst()
            }
            conversationHistory.addLast(clean)
            Log.i(TAG, "📚 [Context Window Updated] (${conversationHistory.size}/$MAX_CONTEXT_SENTENCES items)")
            Log.d(TAG, "   Current Buffer: ${conversationHistory.joinToString(" | ")}")
        }
    }

    @Synchronized
    fun recordSilenceEvent(durationMs: Double) {
        lastRecordedSilenceMs = durationMs
        Log.d(TAG, "🔇 [Silence Event Logged] Duration: ${durationMs.toInt()}ms")
    }

    @Synchronized
    fun evaluateSentenceWithAcoustics(
        recognizedSentence: String,
        matchedCodeword: String,
        rmsEnergy: Double,
        precedingSilenceMs: Double
    ): Boolean {
        // Use the highest silence duration between the immediate frame count or recent block log
        val effectiveSilence = maxOf(precedingSilenceMs, lastRecordedSilenceMs)

        Log.i(TAG, "🎛️ [Acoustic & Context Evaluation]")
        Log.i(TAG, "   RMS Energy: $rmsEnergy | Effective Silence: ${effectiveSilence.toInt()}ms")

        val similarity: Float
        if (conversationHistory.isEmpty()) {
            Log.w(TAG, "⚠️ No prior context available. Evaluating sentence alone against codeword.")
            similarity = computeSimilarity(recognizedSentence, matchedCodeword)
            Log.i(TAG, "Isolated similarity: $similarity vs Threshold: $SIMILARITY_THRESHOLD")
        } else {
            val priorContext = conversationHistory.joinToString(" ")
            Log.i(TAG, "🧠 [Evaluating Semantic Cohesion]")
            Log.i(TAG, "   Prior Context: \"$priorContext\"")
            Log.i(TAG, "   Codeword Under Test: \"$matchedCodeword\"")

            similarity = computeSimilarity(priorContext, matchedCodeword)
            Log.i(TAG, "   Cosine Similarity Score: $similarity (Threshold: $SIMILARITY_THRESHOLD)")
        }

        // Dynamic Threshold Modifier:
        // If an abnormal silence block preceded the codeword, it indicates hesitation or coercion,
        // making us slightly more sensitive to triggering an alert (lowering the suppression barrier).
        var adjustedThreshold = SIMILARITY_THRESHOLD
        if (effectiveSilence >= ABNORMAL_SILENCE_THRESHOLD_MS) {
            adjustedThreshold -= SILENCE_SENSITIVITY_BOOST
            Log.w(TAG, "⚡ [Abnormal Pause Detected] Adjusting similarity threshold down to $adjustedThreshold due to ${effectiveSilence.toInt()}ms silence.")
        }

        return if (similarity >= adjustedThreshold) {
            Log.w(TAG, "🛡️️ [FALSE POSITIVE SUPPRESSED] Codeword matches conversation topic (score: $similarity >= $adjustedThreshold).")
            false
        } else {
            Log.e(TAG, "🚨 [AUTHENTIC DISTRESS CONFIRMED] Codeword is an out-of-context anomaly (score: $similarity < $adjustedThreshold).")
            true
        }
    }

    private fun computeSimilarity(text1: String, text2: String): Float {
        val embedder = textEmbedder ?: return 0.0f
        return try {
            val result1 = embedder.embed(text1)
            val result2 = embedder.embed(text2)

            val embedding1: Embedding? = result1.embeddingResult().embeddings().firstOrNull()
            val embedding2: Embedding? = result2.embeddingResult().embeddings().firstOrNull()

            if (embedding1 != null && embedding2 != null) {
                TextEmbedder.cosineSimilarity(embedding1, embedding2).toFloat()
            } else {
                0.0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating cosine similarity: ${e.message}", e)
            0.0f
        }
    }

    @Synchronized
    fun clearHistory() {
        conversationHistory.clear()
        lastRecordedSilenceMs = 0.0
        Log.d(TAG, "Conversation history cleared.")
    }

    fun close() {
        textEmbedder?.close()
        textEmbedder = null
    }
}