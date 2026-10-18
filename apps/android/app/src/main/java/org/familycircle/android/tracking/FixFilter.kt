package org.familycircle.android.tracking

import org.familycircle.android.util.Geo

/**
 * A location fix, independent of `android.location.Location` so the pipeline is JVM-testable.
 *
 * @property timeMs the fix's own UTC time (`Location.time`), never the time we received it.
 * @property accuracyM horizontal accuracy radius (68 %), null when the provider gave none.
 * @property provider `LocationManager` provider name (`gps`, `fused`, `network`, …), null if unknown.
 */
data class Fix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val altitudeM: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String? = null,
)

/**
 * Drops fixes that are useless for the family: no accuracy, accuracy worse than [maxAccuracyM],
 * invalid coordinates/time, or not newer than the last accepted fix (out-of-order delivery from
 * several providers, cached fixes).
 *
 * Plausibility of *movement* (GPS jumps) is intentionally left to the server, which sees the whole
 * track across devices.
 */
class FixFilter(
    private val maxAccuracyM: Float = MAX_ACCURACY_M,
    lastAcceptedTimeMs: Long? = null,
) {
    enum class Rejection { INVALID, NO_ACCURACY, INACCURATE, NOT_NEWER, SUPERSEDED_BY_GPS }

    /** Time of the latest valid GPS fix seen (recorded or not). */
    var lastGpsTimeMs: Long? = null
        private set

    var lastAcceptedTimeMs: Long? = lastAcceptedTimeMs
        private set

    /**
     * Pure check, does not change state. Returns null when the fix is acceptable.
     *
     * @param gpsPriorityMs while a GPS fix is younger than this, fixes from other providers are
     *   dropped. With fused + GPS requested together, a fused provider may re-deliver an older
     *   position with a fresh timestamp between real GPS fixes; interleaving both would create
     *   artificial "jumps". When GPS goes silent (indoors) the other provider takes over.
     */
    fun check(fix: Fix, gpsPriorityMs: Long = 0): Rejection? {
        if (fix.timeMs <= 0 || !Geo.isValidCoordinate(fix.lat, fix.lon)) return Rejection.INVALID
        val acc = fix.accuracyM
        // Location.getAccuracy() returns 0 when the provider has no estimate; the API also requires > 0.
        if (acc == null || !acc.isFinite() || acc <= 0f) return Rejection.NO_ACCURACY
        if (acc > maxAccuracyM) return Rejection.INACCURATE
        val last = lastAcceptedTimeMs
        if (last != null && fix.timeMs <= last) return Rejection.NOT_NEWER
        val gps = lastGpsTimeMs
        if (fix.provider != GPS && gps != null && gpsPriorityMs > 0 && fix.timeMs - gps < gpsPriorityMs) {
            return Rejection.SUPERSEDED_BY_GPS
        }
        return null
    }

    /** Remembers a valid GPS fix for [check]'s provider priority. */
    fun noteValid(fix: Fix) {
        if (fix.provider == GPS && (lastGpsTimeMs ?: Long.MIN_VALUE) < fix.timeMs) lastGpsTimeMs = fix.timeMs
    }

    fun markAccepted(fix: Fix) {
        lastAcceptedTimeMs = fix.timeMs
    }

    /** Convenience: [check] + [markAccepted]. */
    fun accept(fix: Fix): Rejection? = check(fix).also { if (it == null) markAccepted(fix) }

    companion object {
        const val MAX_ACCURACY_M = 2000f
        private const val GPS = "gps"
    }
}
