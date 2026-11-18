package com.praneethmv.expensetracker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Drains the on-phone outbox to Supabase. Retries with backoff when offline or the server errors. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val api = SupabaseApi(applicationContext)
        if (!api.signedIn) return@withContext Result.success() // nothing to do until sign-in; SMS stay queued
        val queue = LocalQueue.get(applicationContext)
        val status = applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
        try {
            var parsed = 0
            var unparsed = 0
            while (true) {
                val batch = queue.pending(BATCH)
                if (batch.isEmpty()) break
                val r = api.ingest(batch)
                queue.markUploaded(batch.map { it.hash })
                parsed += r.parsed
                unparsed += r.unparsed
            }
            status.edit()
                .putLong("last_ok", System.currentTimeMillis())
                .putString("last_msg", "Synced. New: $parsed parsed, $unparsed need review")
                .apply()
            Result.success()
        } catch (e: AuthException) {
            status.edit().putString("last_msg", e.message).apply()
            Result.success() // needs the user to sign in; don't spin
        } catch (e: IOException) {
            status.edit().putString("last_msg", "Will retry: ${e.message}").apply()
            Result.retry()
        } catch (e: Exception) {
            status.edit().putString("last_msg", "Will retry: ${e.javaClass.simpleName} ${e.message}").apply()
            Result.retry()
        }
    }

    companion object {
        private const val BATCH = 150
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("upload", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        /** Safety net in case a live upload was missed. */
        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UploadWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("upload-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
