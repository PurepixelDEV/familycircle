package org.familycircle.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.familycircle.android.FamilyCircleApp
import org.familycircle.android.data.prefs.AppPreferences
import org.familycircle.android.util.Logger
import java.util.concurrent.TimeUnit

/**
 * Runs the [Uploader], records the outcome for the status screen and, if points are left over after
 * a retryable failure, enqueues the [UploadWorker] as catch-up.
 */
class SyncManager(
    private val context: Context,
    private val uploader: Uploader,
    private val prefs: AppPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(scheduleCatchUp: Boolean): UploadResult {
        val result = uploader.uploadAll()
        val nothingToDo = result.uploaded == 0 && result.rejected == 0 && result.error == null
        if (!nothingToDo) {
            prefs.setUploadResult(clock(), result.success, result.error?.key(), result.uploaded)
        }
        Logger.i(TAG, "upload: sent=${result.uploaded} rejected=${result.rejected} left=${result.remaining} error=${result.error?.key()}")
        if (scheduleCatchUp && result.remaining > 0 && result.error?.retryable == true) {
            UploadScheduler.enqueue(context)
        }
        return result
    }

    private companion object {
        const val TAG = "Sync"
    }
}

/** Short keys persisted in prefs and mapped to localised texts by the UI. */
fun UploadError.key(): String = when (this) {
    UploadError.Network -> "network"
    is UploadError.Transient -> if (status == 429) "rate_limited" else "server_unavailable"
    UploadError.Unauthorized -> "unauthorized"
    UploadError.NotConfigured -> "not_configured"
    is UploadError.Permanent -> "rejected"
}

/**
 * Catch-up upload when the foreground service is not running (tracking paused/stopped with points
 * left, or the network came back). Uses WorkManager only as a fallback: on Android 16 job runtime
 * quotas also apply while a foreground service runs, so the service uploads by itself.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FamilyCircleApp).container
        if (!container.auth.isLoggedIn) return Result.failure()
        val result = container.sync.sync(scheduleCatchUp = false)
        val error = result.error
        return when {
            error == null -> Result.success()
            error.retryable -> Result.retry()
            else -> Result.failure()
        }
    }
}

object UploadScheduler {
    private const val UNIQUE_NAME = "familycircle-upload"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
