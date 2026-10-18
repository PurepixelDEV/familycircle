package org.familycircle.android.tracking

import android.content.Context
import android.location.Location
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.familycircle.android.data.prefs.AppPreferences
import org.familycircle.android.data.queue.PendingLocation
import org.familycircle.android.data.queue.PendingLocationDao
import org.familycircle.android.map.LocalFix
import org.familycircle.android.util.Logger

/** Reads the current battery level without registering a receiver. */
object BatteryReader {
    fun read(context: Context): BatteryState? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        return BatteryState(percent = pct, charging = bm.isCharging)
    }
}

fun Location.toFix(): Fix = Fix(
    timeMs = time,
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else null,
    altitudeM = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed else null,
    bearingDeg = if (hasBearing()) bearing else null,
    provider = provider,
)

/**
 * Persists accepted fixes. Every fix is written to Room **before** any upload is attempted, so
 * nothing is lost when the network, the server or the process goes away.
 *
 * Shared by the foreground service and the one-shot "send my location now" action; a [Mutex]
 * serialises them so the [FixProcessor] state stays consistent.
 */
class LocationRecorder(
    private val context: Context,
    private val dao: PendingLocationDao,
    private val prefs: AppPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var processor: FixProcessor? = null
    private var inserts = 0

    private val _latest = MutableStateFlow<LocalFix?>(null)

    /**
     * Latest fix recorded on this phone in this process (for the own marker on the map). Kept in
     * memory only; [loadLatestQueued] seeds it from the queue after a process restart.
     */
    val latest: StateFlow<LocalFix?> = _latest.asStateFlow()

    /** Last motion class seen (used by the policy to relax intervals while stationary). */
    @Volatile
    var lastMotion: Motion = Motion.UNKNOWN
        private set

    /**
     * @param params current tracking parameters, or null for a one-shot fix (no thinning).
     * @return the recorded queue entry, or null when the fix was dropped.
     */
    suspend fun record(location: Location, params: LocationParams?): PendingLocation? = mutex.withLock {
        val proc = processor ?: FixProcessor(FixFilter(lastAcceptedTimeMs = prefs.current.lastFixAtMs))
            .also { processor = it }
        val result = proc.process(location.toFix(), params)
        lastMotion = proc.lastMotion
        when (result) {
            is FixProcessor.Result.Drop -> {
                Logger.d(TAG) { "fix dropped: ${result.reason}" }
                null
            }
            is FixProcessor.Result.Record -> {
                val battery = if (prefs.current.shareBattery) BatteryReader.read(context) else null
                val point = PendingLocation.from(result.fix, result.motion, battery, createdAtMs = clock())
                dao.insert(point)
                prefs.setLastFix(result.fix.timeMs, point.accuracyM.toDouble())
                _latest.value = point.toLocalFix()
                if (inserts++ % PRUNE_EVERY == 0) {
                    val pruned = dao.deleteRecordedBefore(clock() - MAX_AGE_MS)
                    if (pruned > 0) Logger.i(TAG, "pruned $pruned points older than 7 days")
                }
                Logger.d(TAG) { "fix recorded (${result.motion.apiValue})" }
                point
            }
        }
    }

    /** Seeds [latest] from the newest queued (not yet uploaded) point, if nothing newer is known. */
    suspend fun loadLatestQueued() {
        val newest = dao.newest() ?: return
        val current = _latest.value
        if (current == null || newest.recordedAtMs > current.recordedAtMs) _latest.value = newest.toLocalFix()
    }

    /** Forget filter state (after sign-out / account change). */
    suspend fun reset() = mutex.withLock {
        processor = null
        lastMotion = Motion.UNKNOWN
        _latest.value = null
    }

    private fun PendingLocation.toLocalFix() = LocalFix(
        recordedAtMs = recordedAtMs,
        lat = lat,
        lon = lon,
        accuracyM = accuracyM.toDouble(),
        motion = motion,
    )

    private companion object {
        const val TAG = "Recorder"
        const val PRUNE_EVERY = 50
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
