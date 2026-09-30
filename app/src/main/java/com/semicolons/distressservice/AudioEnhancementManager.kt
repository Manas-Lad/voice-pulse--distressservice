package com.semicolons.distressservice

import android.media.AudioRecord
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

class AudioEnhancementManager {

    companion object {
        private const val TAG = "AudioEnhance"
    }

    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    fun attachHardwareEffects(audioRecord: AudioRecord) {
        val sessionId = audioRecord.audioSessionId
        if (sessionId == 0) {
            Log.w(TAG, "Invalid audioSessionId (0); skipping hardware effects attachment.")
            return
        }

        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                }
                Log.d(TAG, "Hardware NoiseSuppressor enabled on session $sessionId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to instantiate hardware NoiseSuppressor: ${e.message}")
            }
        } else {
            Log.w(TAG, "Hardware NoiseSuppressor not supported on this device.")
        }

        if (AutomaticGainControl.isAvailable()) {
            try {
                agc = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = true
                }
                Log.d(TAG, "Hardware AutomaticGainControl enabled on session $sessionId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to instantiate hardware AGC: ${e.message}")
            }
        } else {
            Log.w(TAG, "Hardware AGC not supported on this device.")
        }
    }

    /**
     * Fast in-place normalization fallback for ShortArray PCM buffers
     */
    fun boostPcmAudioBuffer(buffer: ShortArray, readSamples: Int, boostFactor: Float = 1.25f) {
        val count = readSamples.coerceAtMost(buffer.size)
        for (i in 0 until count) {
            val sample = buffer[i].toInt()
            buffer[i] = (sample * boostFactor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun release() {
        try {
            if (noiseSuppressor?.hasControl() == true) {
                noiseSuppressor?.enabled = false
            }
            noiseSuppressor?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing NoiseSuppressor: ${e.message}")
        } finally {
            noiseSuppressor = null
        }

        try {
            if (agc?.hasControl() == true) {
                agc?.enabled = false
            }
            agc?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AGC: ${e.message}")
        } finally {
            agc = null
        }
    }
}