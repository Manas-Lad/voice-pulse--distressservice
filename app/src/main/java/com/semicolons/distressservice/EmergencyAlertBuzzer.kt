package com.semicolons.distressservice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

object EmergencyAlertBuzzer {

    private const val TAG =
        "EmergencyAlertBuzzer"

    fun playGovernmentAlertTone(
        context: Context,
        durationSeconds: Double = 6.0
    ) {

        Log.w(
            TAG,
            "========================================"
        )

        Log.w(
            TAG,
            "BUZZER FUNCTION ENTERED"
        )

        Log.w(
            TAG,
            "Requested duration = ${durationSeconds}s"
        )

        Log.w(
            TAG,
            "========================================"
        )

        val sampleRate = 44100

        val beepFrequency1 = 853.0
        val beepFrequency2 = 960.0

        val beepDuration = 0.45
        val pauseDuration = 0.20

        val amplitude = 0.75

        val totalSamples =
            (sampleRate * durationSeconds)
                .toInt()

        Log.d(
            TAG,
            "Sample rate = $sampleRate"
        )

        Log.d(
            TAG,
            "Total samples = $totalSamples"
        )

        Log.d(
            TAG,
            "Frequency 1 = $beepFrequency1 Hz"
        )

        Log.d(
            TAG,
            "Frequency 2 = $beepFrequency2 Hz"
        )

        Log.d(
            TAG,
            "Beep duration = ${beepDuration}s"
        )

        Log.d(
            TAG,
            "Pause duration = ${pauseDuration}s"
        )

        // ========================================================
        // GENERATE PCM
        // ========================================================

        val pcmData =
            ShortArray(totalSamples)

        val beepSamples =
            (sampleRate * beepDuration)
                .toInt()

        val pauseSamples =
            (sampleRate * pauseDuration)
                .toInt()

        val cycleSamples =
            beepSamples + pauseSamples

        var beepNumber = 0

        Log.d(
            TAG,
            "Generating PCM audio data..."
        )

        for (
        sampleIndex in 0 until totalSamples
        ) {

            val cyclePosition =
                sampleIndex % cycleSamples

            if (
                cyclePosition < beepSamples
            ) {

                val time =
                    sampleIndex.toDouble() / sampleRate

                val sample =
                    (
                            (
                                    sin(
                                        2.0 *
                                                PI *
                                                beepFrequency1 *
                                                time
                                    ) +
                                            sin(
                                                2.0 *
                                                        PI *
                                                        beepFrequency2 *
                                                        time
                                            )
                                    ) *
                                    amplitude *
                                    Short.MAX_VALUE
                            )
                        .toInt()
                        .coerceIn(
                            Short.MIN_VALUE.toInt(),
                            Short.MAX_VALUE.toInt()
                        )
                        .toShort()

                pcmData[sampleIndex] =
                    sample

                if (cyclePosition == 0) {

                    beepNumber++

                    Log.d(
                        TAG,
                        "Generating beep #$beepNumber"
                    )
                }

            } else {

                pcmData[sampleIndex] = 0
            }
        }

        Log.d(
            TAG,
            "PCM generation finished."
        )

        Log.d(
            TAG,
            "Generated $beepNumber beeps."
        )

        // ========================================================
        // AUDIO ATTRIBUTES
        // ========================================================

        val audioAttributes =
            AudioAttributes.Builder()
                .setUsage(
                    AudioAttributes.USAGE_ALARM
                )
                .setContentType(
                    AudioAttributes.CONTENT_TYPE_SONIFICATION
                )
                .build()

        // ========================================================
        // AUDIO FORMAT
        // ========================================================

        val audioFormat =
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_OUT_MONO
                )
                .build()

        // ========================================================
        // BUFFER
        // ========================================================

        val minBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        Log.d(
            TAG,
            "Minimum AudioTrack buffer size = $minBufferSize"
        )

        val requiredBufferSize =
            pcmData.size * 2

        // ========================================================
        // AUDIO TRACK
        // ========================================================

        val audioTrack =
            AudioTrack(
                audioAttributes,
                audioFormat,
                requiredBufferSize,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

        Log.d(
            TAG,
            "AudioTrack state = ${audioTrack.state}"
        )

        /*
         * IMPORTANT:
         *
         * MODE_STATIC can initially report
         * STATE_NO_STATIC_DATA (2).
         *
         * That is expected before PCM data is written.
         *
         * Only STATE_UNINITIALIZED means initialization failed.
         */

        if (
            audioTrack.state ==
            AudioTrack.STATE_UNINITIALIZED
        ) {

            Log.e(
                TAG,
                "AudioTrack failed to initialize."
            )

            audioTrack.release()

            return
        }

        // ========================================================
        // WRITE PCM DATA
        // ========================================================

        val written =
            audioTrack.write(
                pcmData,
                0,
                pcmData.size
            )

        Log.d(
            TAG,
            "AudioTrack.write() returned $written"
        )

        if (written < 0) {

            Log.e(
                TAG,
                "AudioTrack.write() failed."
            )

            audioTrack.release()

            return
        }

        // ========================================================
        // MAXIMIZE ALARM VOLUME
        // ========================================================

        try {

            val audioManager =
                context.getSystemService(
                    Context.AUDIO_SERVICE
                ) as AudioManager

            val maxAlarmVolume =
                audioManager.getStreamMaxVolume(
                    AudioManager.STREAM_ALARM
                )

            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                maxAlarmVolume,
                0
            )

            Log.d(
                TAG,
                "Alarm volume set to maximum."
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Could not set alarm volume: ${e.message}"
            )
        }

        // ========================================================
        // PLAY
        // ========================================================

        try {

            Log.w(
                TAG,
                "Starting emergency alert tone."
            )

            audioTrack.play()

            Thread.sleep(
                (durationSeconds * 1000)
                    .toLong()
            )

        } catch (
            e: InterruptedException
        ) {

            Thread.currentThread().interrupt()

            Log.e(
                TAG,
                "Buzzer playback interrupted. ${e.message}"
            )

        } finally {

            try {
                audioTrack.stop()
            } catch (_: Exception) {
            }

            audioTrack.release()

            Log.w(
                TAG,
                "Emergency alert tone finished."
            )
        }
    }
}