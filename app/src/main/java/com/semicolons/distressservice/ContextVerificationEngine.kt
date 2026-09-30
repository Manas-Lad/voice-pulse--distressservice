package com.semicolons.distressservice

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import java.util.ArrayDeque

class ContextVerificationEngine(private val context: Context) {

    companion object {
        private const val TAG = "ContextEngine"
        private const val MODEL_NAME = "universal_sentence_encoder.tflite"
        // If similarity between prior context and codeword >= 0.55, it is a false alarm
        private const val SIMILARITY_THRESHOLD = 0.55
    }

    // Rolling context buffer: holds up to 2 sentences spoken before the trigger
    private val rollingContextWindow = ArrayDeque<String>(2)
    private var textEmbedder: TextEmbedder? = null

    init {
        initEmbedder()
    }

    private fun initEmbedder() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_NAME)
                .build()

            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .build()

            textEmbedder = TextEmbedder.createFromOptions(context, options)
            Log.d(TAG, "MediaPipe TextEmbedder initialized successfully with $MODEL_NAME.")
        } catch (e: Exception) {
            Log.w(TAG, "Model ($MODEL_NAME) failed to load. Will trigger alert as fallback: ${e.message}")
        }
    }

    /**
     * Called whenever Vosk finalizes a sentence/phrase.
     * @return true if authentic emergency (outlier), false if false alarm (cohesive context)
     */
    @Synchronized
    fun evaluateSentence(recognizedSentence: String, matchedCodeword: String): Boolean {
        val cleanSentence = recognizedSentence.trim().lowercase()
        val targetWord = matchedCodeword.trim().lowercase()

        val isAuthentic = evaluateContext(targetWord)

        pushToBuffer(cleanSentence)
        return isAuthentic
    }

    @Synchronized
    fun recordNormalSentence(sentence: String) {
        val clean = sentence.trim().lowercase()
        if (clean.isNotEmpty()) {
            pushToBuffer(clean)
        }
    }

    private fun pushToBuffer(sentence: String) {
        if (rollingContextWindow.size >= 2) {
            rollingContextWindow.removeFirst()
        }
        rollingContextWindow.addLast(sentence)
    }

    private fun evaluateContext(codeword: String): Boolean {
        if (rollingContextWindow.isEmpty()) {
            Log.i(TAG, "No prior context in buffer. Treating trigger as deliberate alert.")
            return true
        }

        val priorContext = rollingContextWindow.joinToString(" ")
        Log.d(TAG, "Evaluating Context: \"$priorContext\" against Codeword: \"$codeword\"")

        val similarity = computeCosineSimilarity(priorContext, codeword)
        Log.d(TAG, "Similarity score: $similarity (Threshold: $SIMILARITY_THRESHOLD)")

        if (similarity >= SIMILARITY_THRESHOLD) {
            Log.w(TAG, "Codeword detected, but FLAGGED AS FALSE POSITIVE (Cohesive conversation).")
            return false // Suppress false alarm
        }

        Log.i(TAG, "Codeword is an out-of-context outlier. AUTHENTIC DISTRESS CONFIRMED.")
        return true
    }

    private fun computeCosineSimilarity(textA: String, textB: String): Double {
        val embedder = textEmbedder ?: return 0.0

        return try {
            val resultA = embedder.embed(textA)
            val resultB = embedder.embed(textB)

            val embeddingA = resultA.embeddingResult().embeddings()[0]
            val embeddingB = resultB.embeddingResult().embeddings()[0]

            TextEmbedder.cosineSimilarity(embeddingA, embeddingB)
        } catch (e: Exception) {
            Log.e(TAG, "Cosine similarity computation failed: ${e.message}", e)
            0.0
        }
    }

    fun close() {
        try {
            textEmbedder?.close()
        } catch (_: Exception) {}
        textEmbedder = null
        rollingContextWindow.clear()
    }
}