package org.familycircle.android.map

import org.familycircle.android.api.MapMember
import org.familycircle.android.ui.family.Freshness
import org.familycircle.android.util.Rfc3339

/** The latest fix recorded on this phone (from the recorder / Room queue). Never leaves the device. */
data class LocalFix(
    val recordedAtMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val motion: String,
)

/** Where a marker's data came from. */
enum class MarkerSource { SERVER, DEVICE }

/**
 * Everything needed to draw one member on the map and in the list. Freshness is already the
 * effective (stricter) value; [freshness] `STALE` can never be shown as live.
 */
data class MarkerSpec(
    val userId: String,
    val name: String,
    val colorHex: String,
    val isSelf: Boolean,
    val hasAvatar: Boolean,
    val avatarVersion: Long?,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    /** Null when the server sent an unparsable `recorded_at` (treated as stale). */
    val recordedAtMs: Long?,
    val freshness: Freshness,
    val motion: String,
    val batteryPct: Int?,
    val batteryCharging: Boolean?,
    val source: MarkerSource,
) {
    /** Accuracy circles are only drawn when they are large enough to matter (same as the web app). */
    val showAccuracyCircle: Boolean get() = accuracyM >= MarkerModel.ACCURACY_CIRCLE_MIN_M

    /** Motion is meaningless for an outdated position. */
    val showMotion: Boolean get() = freshness != Freshness.STALE && motion in MarkerModel.KNOWN_MOTIONS
}

object MarkerModel {
    const val ACCURACY_CIRCLE_MIN_M = 15.0
    val KNOWN_MOTIONS = setOf("stationary", "walking", "driving")
    const val FALLBACK_COLOR = "#1e5a52"
    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    /**
     * Builds the markers for all members with a location.
     *
     * The own member (`is_self`) uses the newer of the server's last location and [localSelf] (the
     * latest fix recorded on this phone), so the map shows "you" even before the upload went
     * through. A local fix is only used when there is an `is_self` member, i.e. never invents
     * members.
     */
    fun build(members: List<MapMember>, localSelf: LocalFix?, nowMs: Long): List<MarkerSpec> =
        members.mapNotNull { member ->
            val server = member.location
            val serverAt = server?.let { Rfc3339.parseMillis(it.recordedAt) }
            val useLocal = member.isSelf && localSelf != null && (server == null || serverAt == null || localSelf.recordedAtMs > serverAt)
            when {
                useLocal -> {
                    val fix = localSelf!!
                    MarkerSpec(
                        userId = member.userId,
                        name = member.displayName,
                        colorHex = color(member.color),
                        isSelf = true,
                        hasAvatar = member.hasAvatar,
                        avatarVersion = member.avatarVersion,
                        lat = fix.lat,
                        lon = fix.lon,
                        accuracyM = fix.accuracyM,
                        recordedAtMs = fix.recordedAtMs,
                        freshness = Freshness.fromAge(fix.recordedAtMs, nowMs),
                        motion = fix.motion,
                        // Battery of the own phone is not shown on the own marker (privacy setting is about others).
                        batteryPct = null,
                        batteryCharging = null,
                        source = MarkerSource.DEVICE,
                    )
                }
                server != null -> MarkerSpec(
                    userId = member.userId,
                    name = member.displayName,
                    colorHex = color(member.color),
                    isSelf = member.isSelf,
                    hasAvatar = member.hasAvatar,
                    avatarVersion = member.avatarVersion,
                    lat = server.lat,
                    lon = server.lon,
                    accuracyM = server.accuracyM,
                    recordedAtMs = serverAt,
                    freshness = Freshness.effective(server.freshness, server.recordedAt, nowMs),
                    motion = server.motion,
                    batteryPct = server.batteryPct,
                    batteryCharging = server.batteryCharging,
                    source = MarkerSource.SERVER,
                )
                else -> null
            }
        }.filter { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }

    fun color(hex: String?): String = if (hex != null && HEX.matches(hex)) hex.lowercase() else FALLBACK_COLOR

    /** Up to two initials ("Anna Muster" → "AM", "bob" → "B"). */
    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "?"
        val first = parts.first().first().uppercaseChar()
        val second = parts.drop(1).lastOrNull()?.first()?.uppercaseChar()
        return if (second != null) "$first$second" else "$first"
    }
}
