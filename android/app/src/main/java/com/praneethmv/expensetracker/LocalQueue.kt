package com.praneethmv.expensetracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class QueuedSms(val hash: String, val sender: String, val body: String, val sentMs: Long, val receivedMs: Long)

/** On-phone outbox: every bank SMS lands here first, so nothing is lost while offline. */
class LocalQueue private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "outbox.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sms (
                 hash TEXT PRIMARY KEY, sender TEXT NOT NULL, body TEXT NOT NULL,
                 sent_ms INTEGER NOT NULL, received_ms INTEGER NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX sms_pending ON sms(uploaded, received_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Returns true if the SMS was new. */
    fun add(sender: String, body: String, sentMs: Long, receivedMs: Long): Boolean {
        val values = ContentValues().apply {
            put("hash", SmsFilter.hash(sender, body, sentMs))
            put("sender", sender)
            put("body", body)
            put("sent_ms", sentMs)
            put("received_ms", receivedMs)
        }
        return writableDatabase.insertWithOnConflict("sms", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun pending(limit: Int): List<QueuedSms> {
        val out = mutableListOf<QueuedSms>()
        readableDatabase.rawQuery(
            "SELECT hash, sender, body, sent_ms, received_ms FROM sms WHERE uploaded = 0 ORDER BY received_ms LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out += QueuedSms(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))
        }
        return out
    }

    fun markUploaded(hashes: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (h in hashes) db.execSQL("UPDATE sms SET uploaded = 1 WHERE hash = ?", arrayOf(h))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun counts(): Pair<Int, Int> {
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(uploaded = 0), 0), COALESCE(SUM(uploaded = 1), 0) FROM sms", null,
        ).use { c ->
            c.moveToFirst()
            return c.getInt(0) to c.getInt(1)
        }
    }

    companion object {
        @Volatile private var instance: LocalQueue? = null
        fun get(ctx: Context): LocalQueue =
            instance ?: synchronized(this) { instance ?: LocalQueue(ctx.applicationContext).also { instance = it } }
    }
}
