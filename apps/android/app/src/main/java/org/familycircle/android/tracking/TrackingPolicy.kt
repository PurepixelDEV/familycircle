package org.familycircle.android.tracking

/** Requested accuracy/power trade-off; mapped to `LocationRequestCompat.QUALITY_*`. */
enum class LocationQuality { LOW_POWER, BALANCED, HIGH_ACCURACY }

/** Battery snapshot. [percent] is null when the device does not report a level. */
data class BatteryState(val percent: Int?, val charging: Boolean)

/**
 * Parameters for one tracking configuration.
 *
 * @property intervalMs desired interval between fixes (`LocationRequestCompat` interval).
 * @property minUpdateIntervalMs fastest interval we accept when fixes arrive early.
 * @property minDistanceM a new fix is *recorded* when it is at least this far from the last
 *   recorded one …
 * @property heartbeatMs … or when this much time has passed since the last recorded fix. The
 *   heartbeat keeps a stationary person from being shown as "stale" to the family. Distance
 *   thinning is done in the app ([RecordGate]) rather than via `setMinUpdateDistanceMeters`,
 *   because a platform distance filter would also suppress the heartbeat.
 */
data class LocationParams(
    val mode: TrackingMode,
    val intervalMs: Long,
    val minUpdateIntervalMs: Long,
    val minDistanceM: Float,
    val heartbeatMs: Long,
    val quality: LocationQuality,
)

/**
 * Maps the user's chosen [TrackingMode] plus device state to location request parameters.
 *
 * Targets (not guarantees – Doze, OEM battery savers and the provider may deliver less often):
 *
 * | Mode     | Interval | Distance | Heartbeat | Quality       |
 * |----------|----------|----------|-----------|---------------|
 * | SAVER    | 15 min   | 250 m    | 14 min    | low power     |
 * | BALANCED | 2 min    | 50 m     | 5 min     | balanced      |
 * | LIVE     | 15 s     | 10 m     | 1 min     | high accuracy |
 *
 * Adjustments:
 * * **LIVE timeout:** LIVE is only kept for [liveTimeoutMs] (30 min) after it was selected, then
 *   BALANCED is used.
 * * **Low battery:** at ≤ [lowBatteryPercent] % and not charging the mode is stepped down one level
 *   (LIVE → BALANCED → SAVER); it never goes below SAVER while tracking is on.
 * * **Stationary:** in BALANCED the interval is relaxed to 5 min while the user is not moving.
 *   LIVE is left untouched (the user asked for live updates); SAVER is already minimal.
 */
class TrackingPolicy(
    val liveTimeoutMs: Long = LIVE_TIMEOUT_MS,
    val lowBatteryPercent: Int = LOW_BATTERY_PERCENT,
) {
    /**
     * The mode that should actually run.
     *
     * @param liveStartedAtMs when LIVE was selected (wall clock). A missing or future timestamp is
     *   treated as expired so LIVE can never run unbounded by accident.
     */
    fun effectiveMode(
        requested: TrackingMode,
        battery: BatteryState?,
        liveStartedAtMs: Long?,
        nowMs: Long,
    ): TrackingMode {
        if (requested == TrackingMode.OFF) return TrackingMode.OFF
        var mode = requested
        if (mode == TrackingMode.LIVE && isLiveExpired(liveStartedAtMs, nowMs)) mode = TrackingMode.BALANCED
        if (isLowBattery(battery)) mode = mode.stepDown()
        return mode
    }

    fun isLiveExpired(liveStartedAtMs: Long?, nowMs: Long): Boolean {
        if (liveStartedAtMs == null || liveStartedAtMs > nowMs) return true
        return nowMs - liveStartedAtMs >= liveTimeoutMs
    }

    /** Remaining LIVE time in ms, or 0 when expired. */
    fun liveRemainingMs(liveStartedAtMs: Long?, nowMs: Long): Long =
        if (isLiveExpired(liveStartedAtMs, nowMs)) 0 else liveTimeoutMs - (nowMs - liveStartedAtMs!!)

    fun isLowBattery(battery: BatteryState?): Boolean {
        val pct = battery?.percent ?: return false
        return !battery.charging && pct <= lowBatteryPercent
    }

    /** Request parameters for an *effective* mode; null for [TrackingMode.OFF]. */
    fun paramsFor(mode: TrackingMode, stationary: Boolean): LocationParams? = when (mode) {
        TrackingMode.OFF -> null
        TrackingMode.SAVER -> LocationParams(
            mode = mode,
            intervalMs = 15 * MINUTE,
            minUpdateIntervalMs = 5 * MINUTE,
            minDistanceM = 250f,
            heartbeatMs = 14 * MINUTE,
            quality = LocationQuality.LOW_POWER,
        )
        TrackingMode.BALANCED -> LocationParams(
            mode = mode,
            intervalMs = if (stationary) 5 * MINUTE else 2 * MINUTE,
            minUpdateIntervalMs = 30 * SECOND,
            minDistanceM = 50f,
            heartbeatMs = 5 * MINUTE,
            quality = LocationQuality.BALANCED,
        )
        TrackingMode.LIVE -> LocationParams(
            mode = mode,
            intervalMs = 15 * SECOND,
            minUpdateIntervalMs = 5 * SECOND,
            minDistanceM = 10f,
            heartbeatMs = 1 * MINUTE,
            quality = LocationQuality.HIGH_ACCURACY,
        )
    }

    companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val LIVE_TIMEOUT_MS = 30 * MINUTE
        const val LOW_BATTERY_PERCENT = 15
    }
}
