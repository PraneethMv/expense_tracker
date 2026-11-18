package com.praneethmv.expensetracker

import android.content.Context
import android.provider.Telephony
import java.util.concurrent.TimeUnit

/** One-off import of bank SMS already in the inbox. Safe to run repeatedly (hash de-dupes). */
object Backfill {
    /** @return (bank SMS found, newly queued) */
    fun run(ctx: Context, days: Int): Pair<Int, Int> {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        val queue = LocalQueue.get(ctx)
        var found = 0
        var added = 0
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                if (!SmsFilter.shouldUpload(sender, body)) continue
                val received = c.getLong(2)
                val sent = c.getLong(3).takeIf { it > 0 } ?: received
                found++
                if (queue.add(sender, body, sent, received)) added++
            }
        }
        if (added > 0) UploadWorker.enqueue(ctx)
        return found to added
    }
}
