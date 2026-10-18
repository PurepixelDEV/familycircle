package org.familycircle.android.tracking

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.familycircle.android.FamilyCircleApp
import org.familycircle.android.util.Logger
import java.util.concurrent.TimeUnit

/** Why sharing is being resumed without the user opening the app; selects the notice text. */
enum class ResumeReason { BOOT, APP_UPDATED, INTERRUPTED }

/**
 * Sharing that the user switched on only ends by an explicit action (in the app or "Pause"/"Stop"
 * in the notification). If Android ends the service anyway – app swiped away on devices that kill
 * the process, reboot, app update – it is restarted where Android allows it; otherwise the user
 * gets a notice. Pure decision, unit-tested.
 */
object ResumePolicy {
    enum class Action { NONE, RESTART, ASK_USER }

    fun decide(
        desired: Boolean,
        loggedIn: Boolean,
        running: Boolean,
        foregroundPermission: Boolean,
        backgroundPermission: Boolean,
    ): Action = when {
        !desired || !loggedIn || running -> Action.NONE
        // A location foreground service started from the background needs "Allow all the time".
        foregroundPermission && backgroundPermission -> Action.RESTART
        else -> Action.ASK_USER
    }
}

object BackgroundResume {
    private const val TAG = "Resume"

    enum class Outcome { NOT_WANTED, ALREADY_RUNNING, RESTARTED, NOTICE_SHOWN }

    /** Called from the boot/update receiver and the watchdog – never from the UI. */
    fun tryResume(context: Context, reason: ResumeReason): Outcome {
        val container = (context.applicationContext as FamilyCircleApp).container
        val tracking = container.tracking
        val running = TrackingRuntime.running.value
        val action = ResumePolicy.decide(
            desired = container.prefs.current.trackingDesired,
            loggedIn = container.auth.isLoggedIn,
            running = running,
            foregroundPermission = tracking.hasForegroundPermission(),
            backgroundPermission = tracking.hasBackgroundPermission(),
        )
        when (action) {
            ResumePolicy.Action.NONE -> return if (running) Outcome.ALREADY_RUNNING else Outcome.NOT_WANTED
            ResumePolicy.Action.RESTART -> try {
                ContextCompat.startForegroundService(context, LocationService.resumeIntent(context))
                Logger.i(TAG, "resumed ($reason)")
                return Outcome.RESTARTED
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (12+, no exemption such as battery
                // optimisation switched off) or SecurityException.
                Logger.w(TAG, "could not resume ($reason)", e)
            }
            ResumePolicy.Action.ASK_USER -> Unit
        }
        Notifications.ensureChannels(context)
        Notifications.showResumeHint(context, reason)
        return Outcome.NOTICE_SHOWN
    }
}

/**
 * Periodic check while sharing is switched on. Catches the case where a manufacturer's task killer
 * ends the process together with the foreground service when the app is swiped away.
 */
object ResumeWatchdog {
    private const val PERIODIC = "familycircle-resume-watchdog"
    private const val SOON = "familycircle-resume-check"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ResumeWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One check shortly after the app was swiped away. */
    fun checkSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<ResumeWatchdogWorker>().setInitialDelay(1, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(SOON)
    }
}

class ResumeWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        when (BackgroundResume.tryResume(applicationContext, ResumeReason.INTERRUPTED)) {
            // Notice shown: one is enough; starting again from the app schedules a new watchdog.
            BackgroundResume.Outcome.NOT_WANTED, BackgroundResume.Outcome.NOTICE_SHOWN -> ResumeWatchdog.cancel(applicationContext)
            BackgroundResume.Outcome.ALREADY_RUNNING, BackgroundResume.Outcome.RESTARTED -> Unit
        }
        return Result.success()
    }
}

object BatteryOptimization {
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Opens the system dialog to exempt the app from battery optimisation. Needs
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: allowed for apps whose core function (here: continuous
     * location sharing) needs it; the app is not distributed via Google Play. Falls back to the list
     * of all apps if the dialog is not available.
     */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        try {
            context.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // Nothing we can open on this device.
            }
        }
    }
}
