package com.praneethmv.expensetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Fires for every incoming SMS, even when the app is closed. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val now = System.currentTimeMillis()
        val queue = LocalQueue.get(context)
        var added = false
        // A long SMS arrives as several parts; join them per sender, like the inbox does.
        parts.groupBy { it.originatingAddress ?: "" }.forEach { (sender, msgs) ->
            val body = msgs.joinToString("") { it.messageBody ?: "" }
            val sentMs = msgs.first().timestampMillis.takeIf { it > 0 } ?: now
            if (SmsFilter.shouldUpload(sender, body)) {
                added = queue.add(sender, body, sentMs, now) || added
            }
        }
        if (added) UploadWorker.enqueue(context)
    }
}
