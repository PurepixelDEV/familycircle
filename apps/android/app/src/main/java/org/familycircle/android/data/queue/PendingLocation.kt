package org.familycircle.android.data.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.familycircle.android.tracking.BatteryState
import org.familycircle.android.tracking.Fix
import org.familycircle.android.tracking.Motion
import java.util.UUID

/**
 * A recorded fix waiting for upload. [id] is generated on the device and doubles as the server's
 * idempotency key, so re-sending after a lost response is harmless.
 */
@Entity(tableName = "pending_locations", indices = [Index(value = ["recorded_at_ms"])])
data class PendingLocation(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "recorded_at_ms") val recordedAtMs: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float,
    @ColumnInfo(name = "altitude_m") val altitudeM: Double?,
    @ColumnInfo(name = "speed_mps") val speedMps: Float?,
    @ColumnInfo(name = "bearing_deg") val bearingDeg: Float?,
    @ColumnInfo(name = "battery_pct") val batteryPct: Int?,
    @ColumnInfo(name = "battery_charging") val batteryCharging: Boolean?,
    val motion: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
) {
    companion object {
        /**
         * Builds a queue entry, dropping optional values the server would reject (it validates the
         * same ranges, see `validatePoint` in services/api) so one odd sensor value cannot get a
         * whole point rejected.
         *
         * @param battery only included when the user shares the battery level.
         */
        fun from(
            fix: Fix,
            motion: Motion,
            battery: BatteryState?,
            createdAtMs: Long,
            id: String = UUID.randomUUID().toString(),
        ): PendingLocation = PendingLocation(
            id = id,
            recordedAtMs = fix.timeMs,
            lat = fix.lat,
            lon = fix.lon,
            accuracyM = requireNotNull(fix.accuracyM) { "fix must have passed FixFilter" },
            altitudeM = fix.altitudeM?.takeIf { it.isFinite() && it in -1000.0..20_000.0 },
            speedMps = fix.speedMps?.takeIf { it.isFinite() && it in 0f..400f },
            bearingDeg = fix.bearingDeg?.takeIf { it.isFinite() && it >= 0f && it < 360f },
            batteryPct = battery?.percent?.takeIf { it in 0..100 },
            batteryCharging = battery?.takeIf { it.percent != null }?.charging,
            motion = motion.apiValue,
            createdAtMs = createdAtMs,
        )
    }
}
