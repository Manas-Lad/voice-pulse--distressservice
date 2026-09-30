package com.semicolons.distressservice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

object EmergencyAlertBuzzer {

    private const val TAG = "EmergencyAlertBuzzer"

    fun playGovernmentAlertTone(
        context: Context,
        durationSeconds: Double = 6.0
    ) {
        thread(start = true, isDaemon = true, name = "AlertToneThread") {
            executePlayback(context, durationSeconds)
        }
    }

    private fun executePlayback(context: Context, durationSeconds: Double) {
        Log.w(TAG, "Entering emergency buzzer tone generator (duration=${durationSeconds}s)")

        val sampleRate = 44100
        val beepFrequency1 = 853.0
        val beepFrequency2 = 960.0
        val beepDuration = 0.45
        val pauseDuration = 0.20
        val amplitude = 0.85

        val totalSamples = (sampleRate * durationSeconds).toInt()
        val pcmData = ShortArray(totalSamples)

        val beepSamples = (sampleRate * beepDuration).toInt()
        val pauseSamples = (sampleRate * pauseDuration).toInt()
        val cycleSamples = beepSamples + pauseSamples

        for (sampleIndex in 0 until totalSamples) {
            val cyclePosition = sampleIndex % cycleSamples
            if (cyclePosition < beepSamples) {
                val time = sampleIndex.toDouble() / sampleRate
                val rawSample = (sin(2.0 * PI * beepFrequency1 * time) + sin(2.0 * PI * beepFrequency2 * time)) * amplitude * Short.MAX_VALUE
                pcmData[sampleIndex] = rawSample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            } else {
                pcmData[sampleIndex] = 0
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val requiredBufferSize = pcmData.size * 2
        var audioTrack: AudioTrack? = null

        try {
            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                requiredBufferSize,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (audioTrack.state == AudioTrack.STATE_UNINITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize.")
                return
            }

            val written = audioTrack.write(pcmData, 0, pcmData.size)
            if (written < 0) {
                Log.e(TAG, "AudioTrack write failed with code: $written")
                return
            }

            // Maximize alarm stream volume
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.let { am ->
                    val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not adjust alarm volume: ${e.message}")
            }

            Log.w(TAG, "Playing emergency tone...")
            audioTrack.play()
            Thread.sleep((durationSeconds * 1000).toLong())

        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Alert tone playback thread interrupted.")
        } catch (e: Exception) {
            Log.e(TAG, "Buzzer execution error: ${e.message}", e)
        } finally {
            try {
                audioTrack?.stop()
            } catch (_: Exception) {}
            try {
                audioTrack?.release()
            } catch (_: Exception) {}
            Log.w(TAG, "Emergency alert tone playback concluded.")
        }
    }
}