package org.familycircle.android.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.familycircle.android.data.prefs.AppPreferences
import org.familycircle.android.data.prefs.TrackingProblem
import org.familycircle.android.sync.SyncManager
import org.familycircle.android.sync.UploadResult
import org.familycircle.android.util.Logger
import kotlin.coroutines.resume

/** Process-wide runtime state of the location service (not persisted). */
object TrackingRuntime {
    val running = MutableStateFlow(false)

    /** Mode actually in effect (after LIVE timeout / low-battery step-down), null when not running. */
    val effectiveMode = MutableStateFlow<TrackingMode?>(null)

    /** Location providers the service currently listens to (diagnostics), empty when not running. */
    val providers = MutableStateFlow<List<String>>(emptyList())
}

/**
 * Entry point for the UI to control tracking. Starting must happen while the app is in the
 * foreground (Android forbids starting a location foreground service from the background, except
 * after boot with background location permission – see [BootReceiver]).
 */
class TrackingController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val recorder: LocationRecorder,
    private val sync: SyncManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface StartResult {
        data object Started : StartResult
        data class Failed(val problem: TrackingProblem) : StartResult
    }

    sealed interface SendNowResult {
        data class Done(val upload: UploadResult) : SendNowResult
        data object NoFix : SendNowResult
        data class Failed(val problem: TrackingProblem) : SendNowResult
    }

    private val locationManager: LocationManager
        get() = context.getSystemService(LocationManager::class.java)

    private val _sendNowRunning = MutableStateFlow(false)
    val sendNowRunning: StateFlow<Boolean> = _sendNowRunning.asStateFlow()

    fun hasForegroundPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPreciseLocation(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** "Allow all the time". Before Android 10 the foreground permission implies it. */
    fun hasBackgroundPermission(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            hasForegroundPermission()
        } else {
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

    fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(locationManager)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Start (or restart) tracking in [mode]. Must be called from the foreground UI. */
    fun start(mode: TrackingMode): StartResult {
        val effective = if (mode == TrackingMode.OFF) TrackingMode.DEFAULT else mode
        precheck()?.let { problem ->
            prefs.setTrackingProblem(problem)
            return StartResult.Failed(problem)
        }
        prefs.setTrackingMode(effective, if (effective == TrackingMode.LIVE) clock() else null)
        prefs.setTracking(desired = true, pausedByUser = false)
        return try {
            ContextCompat.startForegroundService(context, LocationService.startIntent(context))
            Notifications.cancelResumeHint(context)
            StartResult.Started
        } catch (e: Exception) {
            // IllegalStateException (ForegroundServiceStartNotAllowedException on 12+) or SecurityException.
            Logger.w(TAG, "could not start location service", e)
            prefs.setTracking(desired = false, pausedByUser = false, problem = TrackingProblem.START_NOT_ALLOWED)
            StartResult.Failed(TrackingProblem.START_NOT_ALLOWED)
        }
    }

    /** Change the mode; a running service picks it up via the preferences flow. */
    fun setMode(mode: TrackingMode) {
        if (mode == TrackingMode.OFF) {
            stop()
            return
        }
        val liveStart = if (mode == TrackingMode.LIVE) clock() else null
        prefs.setTrackingMode(mode, liveStart)
    }

    fun stop(problem: TrackingProblem? = null) {
        prefs.setTracking(desired = false, pausedByUser = false, problem = problem)
        context.stopService(LocationService.startIntent(context))
    }

    /** "Pause" from the notification: like stop, but the UI offers to resume. */
    fun pause() {
        prefs.setTracking(desired = false, pausedByUser = true)
    }

    /** One-shot fix → queue → upload. Works with and without the running service. */
    @SuppressLint("MissingPermission") // checked in precheck()
    suspend fun sendNow(): SendNowResult {
        precheck()?.let { return SendNowResult.Failed(it) }
        _sendNowRunning.value = true
        try {
            val lm = locationManager
            val provider = ProviderSelector.selectOneShot(availableProviders(lm))
                ?: return SendNowResult.Failed(TrackingProblem.NO_PROVIDER)
            val location = withTimeoutOrNull(ONE_SHOT_TIMEOUT_MS) { currentLocation(lm, provider) }
                ?: return SendNowResult.NoFix
            recorder.record(location, params = null) ?: return SendNowResult.NoFix
            return SendNowResult.Done(sync.sync(scheduleCatchUp = true))
        } catch (e: SecurityException) {
            Logger.w(TAG, "one-shot location denied", e)
            return SendNowResult.Failed(TrackingProblem.PERMISSION_MISSING)
        } finally {
            _sendNowRunning.value = false
        }
    }

    private fun precheck(): TrackingProblem? = when {
        !hasForegroundPermission() -> TrackingProblem.PERMISSION_MISSING
        !isLocationEnabled() -> TrackingProblem.LOCATION_DISABLED
        else -> null
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                lm,
                provider,
                signal,
                ContextCompat.getMainExecutor(context),
            ) { location -> if (cont.isActive) cont.resume(location) }
        }

    companion object {
        private const val TAG = "Tracking"
        private const val ONE_SHOT_TIMEOUT_MS = 45_000L

        /** Providers that exist on this device (enabled or not). */
        fun availableProviders(lm: LocationManager): Set<String> {
            val candidates = listOf(ProviderSelector.FUSED, ProviderSelector.GPS, ProviderSelector.NETWORK, ProviderSelector.PASSIVE)
            return candidates.filterTo(HashSet()) { LocationManagerCompat.hasProvider(lm, it) }
        }
    }
}
