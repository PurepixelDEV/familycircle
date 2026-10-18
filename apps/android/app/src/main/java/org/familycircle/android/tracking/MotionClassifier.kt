package org.familycircle.android.tracking

import org.familycircle.android.util.Geo
import kotlin.math.max

/**
 * **Heuristic** motion classification from speed. It is a hint for the family ("walking",
 * "driving") and for the power policy – not activity recognition (that would need Play Services
 * or sensor fusion).
 *
 * Speed sources:
 * * the provider's speed (`Location.getSpeed()`, typically from GPS Doppler), if present;
 * * the displacement to the previous fix divided by the elapsed time, if the fixes are
 *   5 s – 30 min apart. A displacement within the larger accuracy radius of the two fixes is
 *   treated as noise (speed 0).
 * When both exist the higher one wins: some chips (and the emulator) report 0 m/s while the
 * position clearly changes, and treating a moving person as stationary would slow updates down.
 * Without either estimate: [Motion.UNKNOWN].
 *
 * Thresholds: < 0.6 m/s stationary, < 2.8 m/s (≈ 10 km/h) walking, above that "driving".
 * Fast running and cycling therefore end up as "driving" – an accepted simplification.
 */
class MotionClassifier(
    private val stationaryBelowMps: Double = 0.6,
    private val walkingBelowMps: Double = 2.8,
) {
    data class Sample(
        val timeMs: Long,
        val lat: Double,
        val lon: Double,
        val accuracyM: Float,
        val speedMps: Float?,
    )

    fun classify(current: Sample, previous: Sample?): Motion {
        val speed = estimateSpeed(current, previous) ?: return Motion.UNKNOWN
        return when {
            speed < stationaryBelowMps -> Motion.STATIONARY
            speed < walkingBelowMps -> Motion.WALKING
            else -> Motion.DRIVING
        }
    }

    /** Estimated speed in m/s, or null when no estimate is possible. */
    fun estimateSpeed(current: Sample, previous: Sample?): Double? {
        // Via the decimal string: 2.8f.toDouble() would be 2.7999999… and fall below the threshold.
        val provider = current.speedMps?.takeIf { it.isFinite() && it >= 0f }?.toString()?.toDouble()
        val displacement = displacementSpeed(current, previous)
        return when {
            provider == null -> displacement
            displacement == null -> provider
            else -> max(provider, displacement)
        }
    }

    private fun displacementSpeed(current: Sample, previous: Sample?): Double? {
        if (previous == null) return null
        val dtMs = current.timeMs - previous.timeMs
        if (dtMs < MIN_DT_MS || dtMs > MAX_DT_MS) return null
        val distance = Geo.distanceMeters(previous.lat, previous.lon, current.lat, current.lon)
        val noise = max(current.accuracyM, previous.accuracyM).toDouble()
        if (distance <= noise) return 0.0
        return distance / (dtMs / 1000.0)
    }

    companion object {
        const val MIN_DT_MS = 5_000L
        const val MAX_DT_MS = 30 * 60_000L
    }
}
