package com.praneethmv.expensetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class AuthException(msg: String) : Exception(msg)
class ApiException(val code: Int, msg: String) : IOException("HTTP $code: $msg")

/**
 * Minimal Supabase client over HttpURLConnection (no extra dependencies).
 * Session tokens live in app-private SharedPreferences.
 */
class SupabaseApi(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_ANON_KEY

    val email: String? get() = prefs.getString("email", null)
    val signedIn: Boolean get() = prefs.getString("refresh", null) != null

    // ---------------------------------------------------------------- auth
    fun signIn(email: String, password: String) {
        if (key.isBlank()) throw AuthException("App was built without SUPABASE_ANON_KEY")
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val res = try {
            call("POST", "/auth/v1/token?grant_type=password", body.toString(), token = null)
        } catch (e: ApiException) {
            throw AuthException(if (e.code == 400) "Wrong email or password" else e.message ?: "Sign-in failed")
        }
        saveSession(JSONObject(res))
    }

    fun signOut() = prefs.edit().clear().apply()

    private fun saveSession(j: JSONObject) {
        prefs.edit()
            .putString("access", j.getString("access_token"))
            .putString("refresh", j.getString("refresh_token"))
            .putLong("expires_at", System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600))
            .putString("email", j.optJSONObject("user")?.optString("email") ?: email)
            .apply()
    }

    @Synchronized
    private fun refresh() {
        val rt = prefs.getString("refresh", null) ?: throw AuthException("Not signed in")
        val res = try {
            call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", rt).toString(), null)
        } catch (e: ApiException) {
            if (e.code in 400..499) { signOut(); throw AuthException("Session expired, sign in again") }
            throw e
        }
        saveSession(JSONObject(res))
    }

    private fun accessToken(): String {
        if (!signedIn) throw AuthException("Not signed in")
        if (prefs.getLong("expires_at", 0) - 60 < System.currentTimeMillis() / 1000) refresh()
        return prefs.getString("access", null) ?: throw AuthException("Not signed in")
    }

    /** Calls with the user's token, refreshing once on 401. */
    private fun authed(method: String, path: String, body: String?, headers: Map<String, String> = emptyMap(),
                       onHeaders: ((HttpURLConnection) -> Unit)? = null): String =
        try {
            call(method, path, body, accessToken(), headers, onHeaders)
        } catch (e: ApiException) {
            if (e.code != 401) throw e
            refresh()
            call(method, path, body, accessToken(), headers, onHeaders)
        }

    // ---------------------------------------------------------------- data
    data class IngestResult(val received: Int, val new: Int, val parsed: Int, val unparsed: Int, val ignored: Int)

    fun ingest(batch: List<QueuedSms>): IngestResult {
        val arr = JSONArray()
        for (s in batch) {
            arr.put(JSONObject()
                .put("hash", s.hash).put("sender", s.sender).put("body", s.body)
                .put("sent_at", Instant.ofEpochMilli(s.sentMs).toString())
                .put("received_at", Instant.ofEpochMilli(s.receivedMs).toString()))
        }
        val j = JSONObject(authed("POST", "/functions/v1/ingest-sms", JSONObject().put("messages", arr).toString()))
        return IngestResult(j.optInt("received"), j.optInt("new"), j.optInt("parsed"), j.optInt("unparsed"), j.optInt("ignored"))
    }

    /** Re-runs the server parser over every stored SMS (after a parser fix or adding cards). */
    fun reparseAll(): Int {
        var after: Long? = 0
        var total = 0
        while (after != null) {
            val j = JSONObject(authed("POST", "/functions/v1/ingest-sms",
                JSONObject().put("reparse", true).put("after_id", after).toString()))
            total += j.optInt("parsed")
            after = if (j.isNull("next_after_id")) null else j.getLong("next_after_id")
        }
        return total
    }

    data class Txn(val kind: String, val amount: String, val merchant: String, val instrument: String?,
                   val time: String?, val channel: String?)

    fun recentTransactions(limit: Int = 100): List<Txn> {
        val q = "select=kind,amount,merchant,instrument_id,txn_time,channel&order=txn_time.desc.nullslast&limit=$limit"
        val arr = JSONArray(authed("GET", "/rest/v1/transactions?$q", null))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Txn(o.getString("kind"), o.get("amount").toString(), o.optString("merchant", "?"),
                o.optString("instrument_id").takeIf { !o.isNull("instrument_id") },
                o.optString("txn_time").takeIf { !o.isNull("txn_time") },
                o.optString("channel").takeIf { !o.isNull("channel") })
        }
    }

    data class Unparsed(val sender: String, val body: String, val receivedAt: String)

    fun unparsed(limit: Int = 30): List<Unparsed> {
        val q = "select=sender,body,received_at&parse_status=eq.unparsed&order=received_at.desc&limit=$limit"
        val arr = JSONArray(authed("GET", "/rest/v1/raw_sms?$q", null))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Unparsed(o.getString("sender"), o.getString("body"), o.getString("received_at"))
        }
    }

    // ---------------------------------------------------------------- http
    private fun call(method: String, path: String, body: String?, token: String?,
                     headers: Map<String, String> = emptyMap(),
                     onHeaders: ((HttpURLConnection) -> Unit)? = null): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer ${token ?: key}")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, text.take(300))
            onHeaders?.invoke(conn)
            return text
        } finally {
            conn.disconnect()
        }
    }
}
