package org.familycircle.android.alerts

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.familycircle.android.FamilyCircleApp
import org.familycircle.android.api.AuthSession
import org.familycircle.android.util.Logger
import java.util.concurrent.TimeUnit

/**
 * "Alarme immer sofort empfangen": a lightweight foreground service (type `specialUse` – family
 * emergency alerts) that only keeps the shared live connection open, so SOS and ring requests
 * arrive within seconds even when location sharing is off. Persistent low-importance notification.
 * Without it, alerts arrive while sharing runs and otherwise via [AlertSyncWorker] (≤ 15 min).
 */
class AlertService : LifecycleService() {
    private val container get() = (application as FamilyCircleApp).container
    private var holding = false
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        AlertNotifier.ensureChannels(this)
        lifecycleScope.launch {
            combine(container.prefs.state, container.auth.state) { p, a -> p.alertsAlwaysOn && a == AuthSession.State.LoggedIn }
                .distinctUntilChanged()
                .collect { wanted -> if (!wanted) shutdown() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!container.prefs.current.alertsAlwaysOn || !container.auth.isLoggedIn) {
            shutdown()
            return START_NOT_STICKY
        }
        // Called for every start (also when already running): each startForegroundService() needs it.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, AlertNotifier.ID_ALERT_SERVICE, AlertNotifier.service(this), type)
            foreground = true
            running = true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (IllegalStateException) when started from the background.
            Logger.w(TAG, "startForeground failed", e)
            shutdown()
            return START_NOT_STICKY
        }
        if (!holding) {
            container.liveHub.hold(HOLDER)
            holding = true
            Logger.i(TAG, "receiving alerts live")
        }
        return START_STICKY
    }

    private fun shutdown() {
        releaseHold()
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        running = false
        stopSelf()
    }

    private fun releaseHold() {
        if (holding) container.liveHub.release(HOLDER)
        holding = false
    }

    override fun onDestroy() {
        releaseHold()
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlertService"
        const val HOLDER = "alerts"

        /** True while the service is in the foreground (process-wide, not persisted). */
        @Volatile
        var running = false
            private set

        /** Starts the service if the user wants it. Returns false when Android refused (background). */
        fun startIfWanted(context: Context): Boolean {
            val container = (context.applicationContext as FamilyCircleApp).container
            if (!container.prefs.current.alertsAlwaysOn || !container.auth.isLoggedIn) return false
            if (running) return true
            return try {
                ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java))
                true
            } catch (e: Exception) {
                Logger.w(TAG, "could not start alert service", e)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertService::class.java))
        }
    }
}

/** Periodic catch-up (`GET /alerts?after=`) while signed in, at least every 15 minutes. */
object AlertSync {
    private const val PERIODIC = "familycircle-alert-sync"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AlertSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class AlertSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FamilyCircleApp).container
        if (!container.auth.isLoggedIn) {
            AlertSync.cancel(applicationContext)
            return Result.success()
        }
        val ok = container.alerts.catchUp()
        // The "always receive alarms" service may have been ended by Android; try to bring it back.
        AlertService.startIfWanted(applicationContext)
        // A ring or locate request found here must finish before the worker (and maybe the process) ends.
        container.alertEffects.awaitIdle(Ringer.MAX_MS + 15_000)
        return if (ok) Result.success() else Result.retry()
    }
}
