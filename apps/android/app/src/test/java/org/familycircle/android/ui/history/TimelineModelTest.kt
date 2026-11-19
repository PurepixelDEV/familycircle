package org.familycircle.android.ui.history

import org.familycircle.android.api.Alert
import org.familycircle.android.api.AlertActor
import org.familycircle.android.api.HistoryPoint
import org.familycircle.android.api.Place
import org.familycircle.android.util.Rfc3339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Same scenarios as the web app's apps/web/src/lib/timeline.test.ts – both must agree. */
class TimelineModelTest {
    private val t0 = Instant.parse("2026-11-16T06:00:00Z").toEpochMilli()
    private fun min(n: Int) = t0 + n * 60_000L
    private val home = 47.0 to 8.0
    private val school = 47.0135 to 8.0 // ~1.5 km north

    private fun hp(
        at: Long,
        lat: Double,
        lon: Double,
        acc: Double = 10.0,
        suspect: Boolean = false,
        motion: String = "unknown",
        speedMps: Double? = null,
        battery: Int? = null,
    ) = HistoryPoint(
        id = at.toString(), suspect = suspect, lat = lat, lon = lon, accuracyM = acc, speedMps = speedMps,
        batteryPct = battery, motion = motion, recordedAt = Rfc3339.format(at),
    )

    private fun tp(at: Long, lat: Double, lon: Double, acc: Double = 10.0, battery: Int? = null) = TPoint(at, lat, lon, acc, battery = battery)

    /** Points every [step] minutes from [a] to [b] (inclusive) around a spot, with ~1 m jitter. */
    private fun stayPoints(a: Int, b: Int, spot: Pair<Double, Double>, step: Int = 5): List<HistoryPoint> =
        (a..b step step).mapIndexed { i, m -> hp(min(m), spot.first + (i % 2) * 0.00001, spot.second, motion = "stationary", battery = 80 - i) }

    private val homePlace = Place(id = "home", familyId = "f", name = "Zuhause", lat = 47.0, lon = 8.0, radiusM = 100, createdAt = "")

    /** 07:00–08:00 at home, walk 08:00–08:20, school until 12:00, no data until 14:00, home again. */
    private fun schoolDay(): List<HistoryPoint> {
        val walk = (0 until 9).map { i -> hp(min(122 + i * 2), home.first + (i + 1) * 0.00135, home.second, motion = "walking", speedMps = 1.3) }
        return stayPoints(60, 120, home) + walk + stayPoints(140, 360, school) + stayPoints(480, 540, home)
    }

    @Test
    fun `usable points drop GPS jumps and vague positions and sort oldest first`() {
        val pts = TimelineModel.usablePoints(
            listOf(hp(min(2), 47.0, 8.0), hp(min(1), 47.0, 8.0), hp(min(3), 10.0, 10.0, suspect = true), hp(min(4), 47.0, 8.0, acc = 900.0)),
        )
        assertEquals(listOf(min(1), min(2)), pts.map { it.t })
    }

    @Test
    fun `distance rule ignores jitter shorter than the accuracy and counts real steps`() {
        assertEquals(0.0, TimelineModel.stepM(tp(0, 47.0, 8.0), tp(1, 47.0001, 8.0)), 0.0) // 11 m
        assertEquals(0.0, TimelineModel.stepM(tp(0, 47.0, 8.0, acc = 60.0), tp(1, 47.0005, 8.0)), 0.0) // 56 m < 60 m
        assertEquals(111.2, TimelineModel.stepM(tp(0, 47.0, 8.0), tp(1, 47.001, 8.0)), 0.5)
        assertTrue(TimelineModel.pathM(listOf(tp(0, 47.0, 8.0), tp(1, 47.0001, 8.0), tp(2, 47.01, 8.0))) > 1090)
    }

    @Test
    fun `a school day becomes stays, a walk and a gap`() {
        val pts = TimelineModel.usablePoints(schoolDay())
        val segs = TimelineModel.buildTimeline(pts, listOf(homePlace))
        assertEquals(listOf("Stay", "Move", "Stay", "Gap", "Stay"), segs.map { it::class.simpleName })

        val h = segs[0] as Stay
        assertEquals(min(60), h.from)
        assertEquals(min(120), h.to)
        assertEquals("Zuhause", h.place?.name)

        val walk = segs[1] as Move
        assertEquals(min(120), walk.from)
        assertEquals(min(140), walk.to)
        assertEquals(MoveMode.WALK, walk.mode)
        assertTrue(walk.distanceM in 1400.0..1600.0)
        assertTrue(walk.avgKmh in 4.0..5.0)

        val sch = segs[2] as Stay
        assertEquals(min(140), sch.from)
        assertEquals(min(360), sch.to)
        assertNull(sch.place)

        val gap = segs[3] as Gap
        assertEquals(min(360), gap.from)
        assertEquals(min(480), gap.to)
        assertEquals("home", (segs[4] as Stay).place?.id)

        val sum = TimelineModel.summarize(pts, segs)
        assertEquals(3, sum.stays)
        assertEquals(1, sum.places)
        assertEquals(20 * 60_000L, sum.movingMs)
        assertEquals(min(60), sum.first)
        assertEquals(min(540), sum.last)
        assertEquals(BatterySample(min(60), 80, null), sum.battery.first())
        // The day's distance uses the shared rule over all positions: walk + the jump across the gap.
        assertTrue(sum.distanceM > 2900)
    }

    @Test
    fun `a short excursion is merged into the stay around it`() {
        val pts = TimelineModel.usablePoints(stayPoints(0, 30, home) + hp(min(32), home.first + 0.0014, home.second) + stayPoints(34, 60, home))
        assertEquals(listOf("Stay"), TimelineModel.buildTimeline(pts, emptyList()).map { it::class.simpleName })
    }

    @Test
    fun `fast moves are rides`() {
        val ride = (0 until 6).map { i -> hp(min(i * 2), 47.0 + i * 0.01, 8.0) } // 1.1 km per 2 min ≈ 33 km/h
        val segs = TimelineModel.buildTimeline(TimelineModel.usablePoints(ride), emptyList())
        assertEquals(1, segs.size)
        assertEquals(MoveMode.RIDE, (segs[0] as Move).mode)
    }

    @Test
    fun `a single position is a short stay`() {
        val segs = TimelineModel.buildTimeline(TimelineModel.usablePoints(listOf(hp(min(0), 47.0, 8.0))), listOf(homePlace))
        assertEquals(1, segs.size)
        val s = segs[0] as Stay
        assertEquals(min(0), s.from)
        assertEquals(min(0), s.to)
    }

    @Test
    fun `no positions no timeline`() {
        assertTrue(TimelineModel.buildTimeline(emptyList(), listOf(homePlace)).isEmpty())
    }

    private val playPts = listOf(tp(min(0), 47.0, 8.0, battery = 50), tp(min(10), 47.01, 8.0, battery = 49), tp(min(100), 47.02, 8.0))

    @Test
    fun `playhead interpolates between positions`() {
        val p = TimelineModel.positionAt(playPts, min(5))!!
        assertEquals(47.005, p.lat, 1e-6)
        assertEquals(50, p.battery)
        assertEquals(false, p.noData)
        assertEquals(6.7, p.speedKmh!!, 0.05)
    }

    @Test
    fun `playhead holds the last position during a gap`() {
        val p = TimelineModel.positionAt(playPts, min(50))!!
        assertEquals(47.01, p.lat, 0.0)
        assertTrue(p.noData)
    }

    @Test
    fun `playhead clamps to the first and last position`() {
        assertEquals(47.0, TimelineModel.positionAt(playPts, min(-5))!!.lat, 0.0)
        assertEquals(47.02, TimelineModel.positionAt(playPts, min(500))!!.lat, 0.0)
        assertNull(TimelineModel.positionAt(emptyList(), 0))
    }

    @Test
    fun `monday of a week`() {
        assertEquals(LocalDate.of(2026, 11, 16), TimelineModel.mondayOf(LocalDate.of(2026, 11, 19))) // Thursday
        assertEquals(LocalDate.of(2026, 11, 16), TimelineModel.mondayOf(LocalDate.of(2026, 11, 16)))
        assertEquals(LocalDate.of(2026, 11, 16), TimelineModel.mondayOf(LocalDate.of(2026, 11, 22))) // Sunday
        assertEquals(LocalDate.of(2026, 12, 28), TimelineModel.mondayOf(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `adding days crosses months`() {
        assertEquals(LocalDate.of(2026, 12, 1), TimelineModel.addDays(LocalDate.of(2026, 11, 30), 1))
        assertEquals(LocalDate.of(2026, 2, 28), TimelineModel.addDays(LocalDate.of(2026, 3, 1), -1))
    }

    @Test
    fun `week bounds are 8 increasing local midnights, also across daylight saving`() {
        val zone = ZoneId.of("Europe/Zurich")
        val b = TimelineModel.weekBounds(LocalDate.of(2026, 10, 19), zone) // clocks go back on Sunday 25 Oct
        assertEquals(8, b.size)
        for (i in 1 until b.size) assertTrue(b[i] > b[i - 1])
        assertEquals("2026-10-18T22:00:00.000Z", Rfc3339.format(b[0]))
        assertEquals(25 * 3_600_000L, b[7] - b[6]) // Sunday has 25 hours
    }

    @Test
    fun `distances and durations are formatted like the web`() {
        val de = Locale.GERMAN
        assertEquals("840 m", TimelineModel.formatDistance(843.0, de))
        assertEquals("3,4 km", TimelineModel.formatDistance(3420.0, de))
        assertEquals("24 km", TimelineModel.formatDistance(23_600.0, de))
        assertEquals("3.4 km", TimelineModel.formatDistance(3420.0, Locale.ENGLISH))
        assertEquals("< 1 min", TimelineModel.formatDuration(20_000))
        assertEquals("12 min", TimelineModel.formatDuration(12 * 60_000L))
        assertEquals("1 h 25 min", TimelineModel.formatDuration(85 * 60_000L))
        assertEquals("2 h", TimelineModel.formatDuration(120 * 60_000L))
    }

    private fun alert(id: Long, kind: String, at: Long) =
        Alert(id = id, kind = kind, actor = AlertActor("u", "Bob"), createdAt = Rfc3339.format(at))

    @Test
    fun `timeline rows interleave events by time and skip unknown kinds`() {
        val segs = TimelineModel.buildTimeline(TimelineModel.usablePoints(schoolDay()), listOf(homePlace))
        val rows = HistoryRows.of(
            segs,
            listOf(alert(1, "check_in", min(141)), alert(2, "sos", min(400)), alert(3, "future_kind", min(200)), alert(4, "ring", min(150))),
        )
        val kinds = rows.map { r -> if (r is SegmentRow) r.segment::class.simpleName else (r as EventRow).alert.kind }
        assertEquals(listOf("Stay", "Move", "Stay", "check_in", "Gap", "sos", "Stay"), kinds)
        assertEquals(2, HistoryRows.segmentAt(segs, min(141)))
        assertEquals(3, HistoryRows.segmentAt(segs, min(400)))
        assertNotNull(HistoryRows.segmentAt(segs, min(60)))
        assertNull(HistoryRows.segmentAt(segs, min(1000)))
    }
}
