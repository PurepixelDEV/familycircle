package org.familycircle.android.tracking

import org.familycircle.android.util.Geo

/**
 * App-side thinning: record a fix when it moved at least [LocationParams.minDistanceM] from the
 * last *recorded* fix or when [LocationParams.heartbeatMs] has elapsed.
 */
class RecordGate {
    private var lastRecorded: Fix? = null

    fun shouldRecord(fix: Fix, params: LocationParams): Boolean {
        val last = lastRecorded ?: return true
        if (fix.timeMs - last.timeMs >= params.heartbeatMs) return true
        return Geo.distanceMeters(last.lat, last.lon, fix.lat, fix.lon) >= params.minDistanceM
    }

    fun markRecorded(fix: Fix) {
        lastRecorded = fix
    }
}

/**
 * The complete per-fix decision: [FixFilter] → motion classification → [RecordGate].
 * Not thread-safe; callers serialise access (see `LocationRecorder`).
 */
class FixProcessor(
    private val filter: FixFilter = FixFilter(),
    private val gate: RecordGate = RecordGate(),
    private val classifier: MotionClassifier = MotionClassifier(),
) {
    sealed interface Result {
        data class Record(val fix: Fix, val motion: Motion) : Result
        data class Drop(val reason: String) : Result
    }

    /** Last fix that passed the filter (recorded or not), used for displacement-based speed. */
    private var lastValid: MotionClassifier.Sample? = null

    /** Most recent motion classification of an accepted fix. */
    var lastMotion: Motion = Motion.UNKNOWN
        private set

    /**
     * @param params current tracking parameters; null for a user-initiated one-shot fix, which
     *   bypasses the [RecordGate] (but not the [FixFilter]).
     */
    fun process(fix: Fix, params: LocationParams?): Result {
        filter.check(fix)?.let { return Result.Drop("filter:${it.name}") }
        val sample = MotionClassifier.Sample(fix.timeMs, fix.lat, fix.lon, fix.accuracyM!!, fix.speedMps)
        val motion = classifier.classify(sample, lastValid)
        lastValid = sample
        lastMotion = motion
        if (params != null && !gate.shouldRecord(fix, params)) return Result.Drop("gate")
        filter.markAccepted(fix)
        gate.markRecorded(fix)
        return Result.Record(fix, motion)
    }
}
