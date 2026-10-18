package org.familycircle.android.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.familycircle.android.FamilyCircleApp
import org.familycircle.android.data.prefs.TrackingProblem
import org.familycircle.android.sync.UploadError
import org.familycircle.android.util.Logger

/**
 * Foreground service of type `location`.
 *
 * * Started from the UI, or from the background with background permission ([BackgroundResume]:
 *   boot, app update, watchdog). Keeps running when the app is swiped away; ends only by the user.
 * * Requests updates per [TrackingPolicy], writes each accepted fix to Room first, then uploads
 *   from a coroutine inside the service (not via WorkManager – Android 16 applies job quotas even
 *   while a foreground service is running).
 * * Reacts to preference changes (mode, stop, pause) via [org.familycircle.android.data.prefs.AppPreferences.state].
 */
class LocationService : LifecycleService() {

    private val container get() = (application as FamilyCircleApp).container
    private val policy = TrackingPolicy()
    private lateinit var locationManager: LocationManager

    /** One listener per provider: the platform allows only one registration per listener. */
    private val listeners = HashMap<String, LocationListenerCompat>()
    private var activeParams: LocationParams? = null
    private var foreground = false
    private var stopping = false

    /** Latest start id; [shutdown] uses stopSelfResult so a start that raced a stop is not lost. */
    private var lastStartId = 0

    private val uploadTrigger = Channel<Unit>(Channel.CONFLATED)

    /** Alerts arrive over the shared live connection while sharing runs (docs/ALERTS.md). */
    private var holdingLive = false
    private lateinit var battery: BatteryAlertMonitor
    private var liveTimer: Job? = null
    private var retryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        Notifications.ensureChannels(this)
        battery = BatteryAlertMonitor(
            context = this,
            scope = container.scope, // outlives the service for the short shutdown request
            latestFix = { container.recorder.latest.value },
            send = { request -> container.api.batteryAlert(request) },
        )
        lifecycleScope.launch { uploadLoop() }
        lifecycleScope.launch {
            container.prefs.state
                .map { Triple(it.trackingDesired, it.trackingMode, it.liveStartedAtMs) }
                .distinctUntilChanged()
                .collect { if (foreground) applyPolicy() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        when (intent?.action) {
            ACTION_PAUSE -> {
                Logger.i(TAG, "paused from notification")
                container.tracking.pause()
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                Logger.i(TAG, "stopped from notification")
                container.tracking.stop()
                shutdown()
                return START_NOT_STICKY
            }
        }
        // A sticky restart (intent == null) after the process was killed lands here as well.
        if (!container.prefs.current.trackingDesired || !container.auth.isLoggedIn) {
            shutdown()
            return START_NOT_STICKY
        }
        stopping = false // a new start may arrive while a previous stop is still pending
        val fromBackground = intent == null || intent.action == ACTION_RESUME
        if (!goForeground(fromBackground)) return START_NOT_STICKY
        applyPolicy()
        uploadTrigger.trySend(Unit) // flush anything queued while we were not running
        return START_STICKY
    }

    /**
     * @param fromBackground restarted by the system or by [BackgroundResume]: if Android refuses,
     * sharing stays switched on ("paused") and a notice asks the user to open the app, instead of
     * reporting it as switched off.
     */
    private fun goForeground(fromBackground: Boolean): Boolean {
        if (!container.tracking.hasForegroundPermission()) {
            fail(TrackingProblem.PERMISSION_MISSING)
            return false
        }
        val notification = Notifications.tracking(this, currentModeForUi(), container.prefs.current.lastFixAtMs)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        return try {
            ServiceCompat.startForeground(this, Notifications.ID_TRACKING, notification, type)
            foreground = true
            TrackingRuntime.running.value = true
            container.prefs.setTrackingProblem(null)
            if (!holdingLive) {
                container.liveHub.hold(LIVE_HOLDER)
                holdingLive = true
            }
            battery.register()
            true
        } catch (e: Exception) {
            // SecurityException (permission revoked / location off on 14+) or
            // ForegroundServiceStartNotAllowedException (IllegalStateException) on 12+.
            Logger.w(TAG, "startForeground failed (fromBackground=$fromBackground)", e)
            if (fromBackground) {
                Notifications.showResumeHint(this, ResumeReason.INTERRUPTED)
                shutdown()
            } else {
                fail(if (e is SecurityException) TrackingProblem.PERMISSION_MISSING else TrackingProblem.START_NOT_ALLOWED)
            }
            false
        }
    }

    /**
     * App swiped away from the recent apps. The foreground service keeps running (stopWithTask is
     * false); some manufacturers kill the process anyway, so the watchdog checks again shortly.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (container.prefs.current.trackingDesired) {
            Logger.i(TAG, "task removed, sharing continues")
            ResumeWatchdog.checkSoon(this)
        }
    }

    /** Re-evaluates mode/battery/motion and (re-)requests updates if the parameters changed. */
    private fun applyPolicy() {
        if (stopping) return
        val prefs = container.prefs.current
        if (!prefs.trackingDesired) {
            shutdown()
            return
        }
        val now = System.currentTimeMillis()
        var requested = prefs.trackingMode
        var liveStartedAt = prefs.liveStartedAtMs
        if (requested == TrackingMode.LIVE && policy.isLiveExpired(liveStartedAt, now)) {
            Logger.i(TAG, "LIVE timed out, falling back to BALANCED")
            requested = TrackingMode.BALANCED
            liveStartedAt = null
            container.prefs.setTrackingMode(requested, null) // re-triggers applyPolicy via the flow; harmless
        }
        val effective = policy.effectiveMode(requested, BatteryReader.read(this), liveStartedAt, now)
        val stationary = container.recorder.lastMotion == Motion.STATIONARY
        val params = policy.paramsFor(effective, stationary)
        if (params == null) {
            shutdown()
            return
        }
        TrackingRuntime.effectiveMode.value = effective
        if (params != activeParams && !requestUpdates(params)) return
        scheduleLiveTimeout(requested, liveStartedAt, now)
        refreshNotification()
    }

    @SuppressLint("MissingPermission") // checked in goForeground(); SecurityException handled below
    private fun requestUpdates(params: LocationParams): Boolean {
        removeUpdates()
        val providers = ProviderSelector.select(TrackingController.availableProviders(locationManager), params.quality)
        if (providers.isEmpty()) {
            fail(TrackingProblem.NO_PROVIDER)
            return false
        }
        val request = LocationRequestCompat.Builder(params.intervalMs)
            .setQuality(
                when (params.quality) {
                    LocationQuality.LOW_POWER -> LocationRequestCompat.QUALITY_LOW_POWER
                    LocationQuality.BALANCED -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
                    LocationQuality.HIGH_ACCURACY -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
                },
            )
            .setMinUpdateIntervalMillis(params.minUpdateIntervalMs)
            .setMinUpdateDistanceMeters(0f) // thinning happens in RecordGate (keeps the heartbeat working)
            .build()
        val executor = ContextCompat.getMainExecutor(this)
        try {
            for (provider in providers) {
                val listener = LocationListenerCompat { location -> onFix(location) }
                LocationManagerCompat.requestLocationUpdates(locationManager, provider, request, executor, listener)
                listeners[provider] = listener
            }
        } catch (e: SecurityException) {
            Logger.w(TAG, "location permission revoked", e)
            fail(TrackingProblem.PERMISSION_MISSING)
            return false
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "provider rejected request", e)
            if (listeners.isEmpty()) {
                fail(TrackingProblem.NO_PROVIDER)
                return false
            }
        }
        activeParams = params
        TrackingRuntime.providers.value = listeners.keys.sorted()
        Logger.i(TAG, "requesting ${params.mode} via ${providers.joinToString()}")
        return true
    }

    private fun removeUpdates() {
        listeners.values.forEach {
            try {
                LocationManagerCompat.removeUpdates(locationManager, it)
            } catch (e: SecurityException) {
                // Permission revoked meanwhile: the platform already dropped our registration.
                Logger.w(TAG, "removeUpdates without permission", e)
            }
        }
        listeners.clear()
        activeParams = null
    }

    private fun onFix(location: android.location.Location) {
        val params = activeParams ?: return
        lifecycleScope.launch {
            val motionBefore = container.recorder.lastMotion
            val recorded = container.recorder.record(location, params)
            if (recorded != null) {
                refreshNotification()
                uploadTrigger.trySend(Unit)
            }
            // Battery level or stationary/moving may have changed the parameters.
            if (container.recorder.lastMotion != motionBefore || recorded != null) applyPolicy()
        }
    }

    private suspend fun uploadLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        for (signal in uploadTrigger) {
            delay(COALESCE_MS) // fixes from several providers arrive close together
            val result = container.sync.sync(scheduleCatchUp = true)
            val error = result.error
            when {
                error == null -> backoffMs = INITIAL_BACKOFF_MS
                error.retryable && result.remaining > 0 -> {
                    val wait = (error as? UploadError.Transient)?.retryAfterSec?.times(1000)?.coerceAtLeast(backoffMs) ?: backoffMs
                    retryJob?.cancel()
                    retryJob = lifecycleScope.launch {
                        delay(wait)
                        uploadTrigger.trySend(Unit)
                    }
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
                // Unauthorized: the session watcher in AppContainer stops tracking and the UI asks
                // the user to sign in again. Permanent errors are shown on the status screen.
                else -> Unit
            }
        }
    }

    private fun scheduleLiveTimeout(requested: TrackingMode, liveStartedAt: Long?, now: Long) {
        liveTimer?.cancel()
        if (requested != TrackingMode.LIVE) return
        val remaining = policy.liveRemainingMs(liveStartedAt, now)
        liveTimer = lifecycleScope.launch {
            delay(remaining + 1_000)
            applyPolicy()
        }
    }

    private fun currentModeForUi(): TrackingMode =
        TrackingRuntime.effectiveMode.value ?: container.prefs.current.trackingMode

    @SuppressLint("MissingPermission") // notify() of an ongoing FGS notification; missing permission only hides it
    private fun refreshNotification() {
        if (!foreground) return
        val notification = Notifications.tracking(this, currentModeForUi(), container.prefs.current.lastFixAtMs)
        try {
            NotificationManagerCompat.from(this).notify(Notifications.ID_TRACKING, notification)
        } catch (e: SecurityException) {
            Logger.w(TAG, "cannot update notification", e)
        }
    }

    private fun fail(problem: TrackingProblem) {
        Logger.w(TAG, "tracking stopped: $problem")
        container.prefs.setTracking(desired = false, pausedByUser = false, problem = problem)
        shutdown()
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        removeUpdates()
        liveTimer?.cancel()
        retryJob?.cancel()
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        releaseLiveAndBattery()
        TrackingRuntime.running.value = false
        TrackingRuntime.effectiveMode.value = null
        TrackingRuntime.providers.value = emptyList()
        stopSelfResult(lastStartId)
    }

    private fun releaseLiveAndBattery() {
        if (holdingLive) container.liveHub.release(LIVE_HOLDER)
        holdingLive = false
        battery.unregister()
    }

    override fun onDestroy() {
        removeUpdates()
        releaseLiveAndBattery()
        TrackingRuntime.running.value = false
        TrackingRuntime.effectiveMode.value = null
        TrackingRuntime.providers.value = emptyList()
        // Points recorded but not yet uploaded are handed to WorkManager.
        container.scheduleCatchUpIfQueued()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LocationService"
        private const val LIVE_HOLDER = "location"
        const val ACTION_START = "org.familycircle.android.action.START"
        const val ACTION_PAUSE = "org.familycircle.android.action.PAUSE"
        const val ACTION_STOP = "org.familycircle.android.action.STOP"

        /** Start from the background (boot, app update, watchdog). */
        const val ACTION_RESUME = "org.familycircle.android.action.RESUME"
        private const val COALESCE_MS = 1_500L
        private const val INITIAL_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 15 * 60_000L

        fun startIntent(context: Context): Intent =
            Intent(context, LocationService::class.java).setAction(ACTION_START)

        fun resumeIntent(context: Context): Intent =
            Intent(context, LocationService::class.java).setAction(ACTION_RESUME)
    }
}
