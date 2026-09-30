package com.semicolons.distressservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import kotlin.concurrent.thread

class EmergencySmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EmergencySmsReceiver"
        private val TRIGGER_KEYWORDS = listOf("SOS", "EMERGENCY", "HELP")
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            return
        }

        val fullMessage = messages.joinToString(separator = "") {
            it.displayMessageBody ?: it.messageBody ?: ""
        }

        Log.d(TAG, "Incoming SMS message: \"$fullMessage\"")

        val hasTrigger = TRIGGER_KEYWORDS.any { keyword ->
            fullMessage.contains(keyword, ignoreCase = true)
        }

        if (!hasTrigger) {
            return
        }

        Log.w(TAG, "Emergency distress SMS trigger detected! Initiating alarm tone...")

        // BroadcastReceivers run on the main thread; use goAsync() to offload work safely
        val pendingResult = goAsync()

        thread(start = true, name = "EmergencySmsWorker") {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG:SmsWakeLock"
            )

            try {
                wakeLock?.acquire(15_000L) // 15-second safety timeout
                EmergencyAlertBuzzer.playGovernmentAlertTone(context, durationSeconds = 6.0)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing emergency alarm tone from SMS: ${e.message}", e)
            } finally {
                if (wakeLock?.isHeld == true) {
                    try {
                        wakeLock.release()
                    } catch (_: Exception) {}
                }
                pendingResult.finish()
            }
        }
    }
}