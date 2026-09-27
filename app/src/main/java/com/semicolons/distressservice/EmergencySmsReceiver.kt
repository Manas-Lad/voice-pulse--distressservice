package com.semicolons.distressservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log

class EmergencySmsReceiver : BroadcastReceiver() {

    companion object {

        private const val TAG =
            "EmergencySmsReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        Log.w(
            TAG,
            "========================================"
        )

        Log.w(
            TAG,
            "SMS RECEIVER onReceive() CALLED"
        )

        Log.w(
            TAG,
            "========================================"
        )

        if (
            intent.action !=
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        ) {

            Log.w(
                TAG,
                "Received unrelated broadcast."
            )

            return
        }

        Log.w(
            TAG,
            "SMS_RECEIVED_ACTION confirmed."
        )

        val messages =
            Telephony.Sms.Intents.getMessagesFromIntent(
                intent
            )

        if (messages.isEmpty()) {

            Log.w(
                TAG,
                "No SMS messages found."
            )

            return
        }

        val fullMessage =
            messages.joinToString(
                separator = ""
            ) {
                it.messageBody ?: ""
            }

        Log.w(
            TAG,
            "SMS BODY = \"$fullMessage\""
        )

        if (
            !fullMessage
                .contains(
                    "SOS",
                    ignoreCase = true
                )
        ) {

            Log.d(
                TAG,
                "SMS does not contain SOS. Ignoring."
            )

            return
        }

        Log.w(
            TAG,
            "SOS DETECTED"
        )

        val powerManager =
            context.getSystemService(
                Context.POWER_SERVICE
            ) as PowerManager

        val wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG:SOSWakeLock"
            )

        try {

            Log.w(
                TAG,
                "ABOUT TO ACQUIRE WAKELOCK"
            )

            wakeLock.acquire(15_000L)

            Log.w(
                TAG,
                "WakeLock acquired = ${wakeLock.isHeld}"
            )

            Log.w(
                TAG,
                "CALLING EmergencyAlertBuzzer NOW"
            )

            EmergencyAlertBuzzer
                .playGovernmentAlertTone(
                    context,
                    6.0
                )

            Log.w(
                TAG,
                "BUZZER CALL FINISHED"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to trigger emergency buzzer: ${e.message}",
                e
            )

        } finally {

            if (wakeLock.isHeld) {
                wakeLock.release()

                Log.w(
                    TAG,
                    "WakeLock released."
                )
            }
        }
    }
}