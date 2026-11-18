package com.praneethmv.expensetracker

import java.security.MessageDigest

/**
 * Decides which SMS leave the phone. Deliberately loose (the server does the real parsing),
 * but personal chats, OTPs and messages without an amount never get uploaded.
 */
object SmsFilter {
    // Indian commercial senders use DLT headers like "AX-HDFCBK", "VM-YESBNK-S", "JD-ICICIT".
    // Personal numbers are digits (optionally with +).
    private val PHONE_NUMBER = Regex("""^\+?\d[\d\s-]*$""")
    private val MONEY = Regex("""(₹|\bRs\.?|\bINR)\s?[\d,]+""", RegexOption.IGNORE_CASE)
    private val TXN_WORDS = Regex(
        """debited|credited|spent|txn|transaction|paid|refund|reversed|withdrawn|received|e-mandate|toll""",
        RegexOption.IGNORE_CASE,
    )
    private val OTP = Regex("""\bOTP\b|one[- ]time password|verification code""", RegexOption.IGNORE_CASE)

    fun shouldUpload(sender: String?, body: String?): Boolean {
        if (sender.isNullOrBlank() || body.isNullOrBlank()) return false
        if (PHONE_NUMBER.matches(sender.trim())) return false
        if (OTP.containsMatchIn(body)) return false
        return MONEY.containsMatchIn(body) && TXN_WORDS.containsMatchIn(body)
    }

    /** Same SMS from the live receiver and from the inbox backfill must hash identically. */
    fun hash(sender: String, body: String, sentMs: Long): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$sender|$body|$sentMs".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
