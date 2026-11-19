package org.familycircle.android.ui.history

import org.familycircle.android.api.HistoryPoint
import org.familycircle.android.api.Place
import org.familycircle.android.util.Geo
import org.familycircle.android.util.Rfc3339
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/*
 * The history timeline of one day: positions → stays, moves and gaps. Port of the web app's
 * apps/web/src/lib/timeline.ts – same constants and rules. The distance rule is also the server's
 * (GET /users/{id}/history/days), so the week overview and the day agree.
 */

/** A plausible position of the day (see [TimelineModel.usablePoints]). */
data class TPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val acc: Double,
    val speedKmh: Double? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val motion: String = "unknown",
) {
    /** Positions never end up in logs. */
    override fun toString(): String = "TPoint($t)"
}

enum class MoveMode { WALK, RIDE }

/** `start`/`end` are inclusive indices into the points the timeline was built from. */
sealed interface Segment {
    val from: Long
    val to: Long
    val start: Int
    val end: Int
}

data class Stay(
    override val from: Long,
    override val to: Long,
    val lat: Double,
    val lon: Double,
    val place: Place?,
    override val start: Int,
    override val end: Int,
) : Segment {
    override fun toString(): String = "Stay($from–$to)"
}

data class Move(
    override val from: Long,
    override val to: Long,
    val distanceM: Double,
    val mode: MoveMode,
    val avgKmh: Double,
    val topKmh: Double?,
    override val start: Int,
    override val end: Int,
) : Segment

data class Gap(
    override val from: Long,
    override val to: Long,
    val distanceM: Double,
    override val start: Int,
    override val end: Int,
) : Segment

data class BatterySample(val t: Long, val pct: Int, val charging: Boolean?)

data class DaySummary(
    val distanceM: Double,
    val movingMs: Long,
    val stays: Int,
    val places: Int,
    val first: Long?,
    val last: Long?,
    val battery: List<BatterySample>,
)

/** Where the person was at a time of the day (playback). */
data class Playhead(val lat: Double, val lon: Double, val battery: Int?, val speedKmh: Double?, val noData: Boolean) {
    override fun toString(): String = "Playhead(noData=$noData)"
}

object TimelineModel {
    /** Positions less accurate than this are left out (too vague for a route). */
    const val MAX_ACCURACY_M = 200.0

    /** A step shorter than max(accuracy of both points, this) is noise of someone standing still. */
    const val MIN_STEP_M = 25.0

    /** Positions within this distance of a group's centre belong to the same stay. */
    const val STAY_RADIUS_M = 100.0

    /** A group of positions is a stay when it lasts at least this long. */
    const val MIN_STAY_MS = 5 * 60_000L

    /** Without a position for longer than this while moving, the timeline shows a gap. */
    const val GAP_MS = 30 * 60_000L

    /** A stay counts as being at a place up to this far outside its radius. */
    const val PLACE_MARGIN_M = 25.0

    /** Average speed above which a move counts as a ride (vehicle, bike) rather than a walk. */
    const val WALK_MAX_KMH = 7.0

    /** Top speed above which a move is a ride even if its average is low (traffic lights, jams). */
    const val RIDE_TOP_KMH = 25.0

    private fun dist(a: TPoint, b: TPoint) = Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon)

    /** Plausible positions, oldest first: no `suspect` GPS jumps, nothing vaguer than [MAX_ACCURACY_M]. */
    fun usablePoints(points: List<HistoryPoint>): List<TPoint> = points
        .asSequence()
        .filter { !it.suspect && it.accuracyM <= MAX_ACCURACY_M }
        .mapNotNull { p ->
            Rfc3339.parseMillis(p.recordedAt)?.let { t ->
                TPoint(t, p.lat, p.lon, p.accuracyM, p.speedMps?.let { it * 3.6 }, p.batteryPct, p.batteryCharging, p.motion)
            }
        }
        .sortedBy { it.t }
        .toList()

    /** Length of the step a → b if it counts as movement, else 0 (shared distance rule). */
    fun stepM(a: TPoint, b: TPoint): Double {
        val d = dist(a, b)
        return if (d >= maxOf(a.acc, b.acc, MIN_STEP_M)) d else 0.0
    }

    /** Distance travelled along pts[start..end] (inclusive). */
    fun pathM(pts: List<TPoint>, start: Int = 0, end: Int = pts.lastIndex): Double {
        var sum = 0.0
        for (i in start + 1..end) sum += stepM(pts[i - 1], pts[i])
        return sum
    }

    private class Cluster(var start: Int, var end: Int, var lat: Double, var lon: Double, var n: Int) {
        fun copy() = Cluster(start, end, lat, lon, n)
    }

    /** Consecutive positions within [STAY_RADIUS_M] of the running centre of their group. */
    private fun clusters(pts: List<TPoint>): List<Cluster> {
        val out = mutableListOf<Cluster>()
        var cur: Cluster? = null
        pts.forEachIndexed { i, p ->
            val c = cur
            if (c != null && Geo.distanceMeters(c.lat, c.lon, p.lat, p.lon) <= STAY_RADIUS_M) {
                c.lat = (c.lat * c.n + p.lat) / (c.n + 1)
                c.lon = (c.lon * c.n + p.lon) / (c.n + 1)
                c.n++
                c.end = i
            } else {
                val next = Cluster(i, i, p.lat, p.lon, 1)
                out += next
                cur = next
            }
        }
        return out
    }

    /** The nearest place whose circle (plus [PLACE_MARGIN_M]) contains the point. */
    fun placeAt(lat: Double, lon: Double, places: List<Place>): Place? {
        var best: Place? = null
        var bestD = Double.POSITIVE_INFINITY
        for (pl in places) {
            val d = Geo.distanceMeters(lat, lon, pl.lat, pl.lon)
            if (d <= pl.radiusM + PLACE_MARGIN_M && d < bestD) {
                best = pl
                bestD = d
            }
        }
        return best
    }

    /** Stays: groups lasting [MIN_STAY_MS]; neighbours at the same spot are merged (a short excursion). */
    private fun findStays(pts: List<TPoint>): List<Cluster> {
        val all = clusters(pts)
        var stays = all.filter { pts[it.end].t - pts[it.start].t >= MIN_STAY_MS }
        // A single short group (one position, or a day that only just started) is still a stay.
        if (stays.isEmpty() && all.size == 1) stays = all
        val merged = mutableListOf<Cluster>()
        for (c in stays) {
            val prev = merged.lastOrNull()
            if (prev != null && Geo.distanceMeters(prev.lat, prev.lon, c.lat, c.lon) <= STAY_RADIUS_M) {
                val n = prev.n + c.n
                prev.lat = (prev.lat * prev.n + c.lat * c.n) / n
                prev.lon = (prev.lon * prev.n + c.lon * c.n) / n
                prev.n = n
                prev.end = c.end
            } else {
                merged += c.copy()
            }
        }
        return merged
    }

    private fun move(pts: List<TPoint>, start: Int, end: Int): Move {
        val from = pts[start].t
        val to = pts[end].t
        val distance = pathM(pts, start, end)
        val hours = (to - from) / 3_600_000.0
        val avgKmh = if (hours > 0) distance / 1000 / hours else 0.0
        var top: Double? = null
        var driving = 0
        var walking = 0
        for (i in start..end) {
            val p = pts[i]
            p.speedKmh?.let { top = max(top ?: 0.0, it) }
            when (p.motion) {
                "driving" -> driving++
                "walking" -> walking++
            }
        }
        val ride = avgKmh > WALK_MAX_KMH || (top ?: 0.0) > RIDE_TOP_KMH || driving > walking
        return Move(from, to, distance, if (ride) MoveMode.RIDE else MoveMode.WALK, avgKmh, top, start, end)
    }

    /** pts[start..end] as moves, split into move / gap / move wherever no position came for [GAP_MS]. */
    private fun moves(pts: List<TPoint>, start: Int, end: Int, out: MutableList<Segment>) {
        var s = start
        for (i in start + 1..end) {
            if (pts[i].t - pts[i - 1].t <= GAP_MS) continue
            if (i - 1 > s) out += move(pts, s, i - 1)
            val a = pts[i - 1]
            val b = pts[i]
            out += Gap(a.t, b.t, dist(a, b), i - 1, i)
            s = i
        }
        if (end > s) out += move(pts, s, end)
    }

    /** The day as stays, moves and gaps in time order (points from [usablePoints]). */
    fun buildTimeline(pts: List<TPoint>, places: List<Place>): List<Segment> {
        val out = mutableListOf<Segment>()
        if (pts.isEmpty()) return out
        var prevEnd = 0
        for (c in findStays(pts)) {
            if (c.start > prevEnd) moves(pts, prevEnd, c.start, out)
            out += Stay(pts[c.start].t, pts[c.end].t, c.lat, c.lon, placeAt(c.lat, c.lon, places), c.start, c.end)
            prevEnd = c.end
        }
        if (out.isEmpty()) {
            moves(pts, 0, pts.lastIndex, out)
        } else if (prevEnd < pts.lastIndex) {
            moves(pts, prevEnd, pts.lastIndex, out)
        }
        return out
    }

    /** Totals of the day. Distance = the shared rule over all positions (equals the week overview). */
    fun summarize(pts: List<TPoint>, segments: List<Segment>): DaySummary {
        val stays = segments.filterIsInstance<Stay>()
        return DaySummary(
            distanceM = pathM(pts),
            movingMs = segments.filterIsInstance<Move>().sumOf { it.to - it.from },
            stays = stays.size,
            places = stays.mapNotNull { it.place?.id }.toSet().size,
            first = pts.firstOrNull()?.t,
            last = pts.lastOrNull()?.t,
            battery = pts.mapNotNull { p -> p.battery?.let { BatterySample(p.t, it, p.charging) } },
        )
    }

    /**
     * Where the person was at time [t]: interpolated between the neighbouring positions, or held at
     * the last one during a gap (noData). Battery and speed come from the last position before t.
     */
    fun positionAt(pts: List<TPoint>, t: Long): Playhead? {
        if (pts.isEmpty()) return null
        var lo = 0
        var hi = pts.lastIndex
        if (t <= pts[0].t) {
            hi = 0
        } else if (t >= pts[hi].t) {
            lo = hi
        } else {
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (pts[mid].t <= t) lo = mid else hi = mid
            }
        }
        val a = pts[lo]
        val b = pts[hi]
        val gap = b.t - a.t > GAP_MS
        val f = if (lo == hi || gap) 0.0 else (t - a.t).toDouble() / (b.t - a.t)
        val speed = when {
            lo == hi || gap -> null
            stepM(a, b) > 0 -> dist(a, b) / 1000 / ((b.t - a.t) / 3_600_000.0)
            else -> 0.0
        }
        return Playhead(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, a.battery, speed, gap && t > a.t)
    }

    /** The Monday of the week that contains [date]. */
    fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** [date] shifted by [n] days. */
    fun addDays(date: LocalDate, n: Long): LocalDate = date.plusDays(n)

    /** Bounds for GET /history/days: local midnights from [monday] for [days] days (plus the end). */
    fun weekBounds(monday: LocalDate, zone: ZoneId, days: Int = 7): List<Long> =
        (0..days).map { monday.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }

    /** Local day → `[from, to)` in epoch millis (handles daylight saving: days of 23/25 h). */
    fun dayRange(day: LocalDate, zone: ZoneId): Pair<Long, Long> =
        day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** "3,4 km" / "850 m" – distances in the timeline. */
    fun formatDistance(m: Double, locale: Locale): String {
        if (m < 1000) return "${(m / 10).roundToLong() * 10} m"
        val nf = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = if (m < 10_000) 1 else 0
            minimumFractionDigits = 0
            roundingMode = RoundingMode.HALF_UP
        }
        return "${nf.format(m / 1000)} km"
    }

    /** "1 h 25 min" / "12 min" / "< 1 min". */
    fun formatDuration(ms: Long): String {
        val min = (ms / 60_000.0).let { floor(it + 0.5).toLong() }
        if (min < 1) return "< 1 min"
        if (min < 60) return "$min min"
        val h = min / 60
        val rest = min % 60
        return if (rest == 0L) "$h h" else "$h h $rest min"
    }
}
