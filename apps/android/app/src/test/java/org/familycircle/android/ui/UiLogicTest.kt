package org.familycircle.android.ui

import org.familycircle.android.ui.family.Freshness
import org.familycircle.android.ui.home.TrackingStatus
import org.familycircle.android.util.RelativeAge
import org.familycircle.android.util.Rfc3339
import org.junit.Assert.assertEquals
import org.junit.Test

class FreshnessTest {
    private val now = Rfc3339.parseMillis("2026-10-18T12:00:00Z")!!

    @Test
    fun `local thresholds`() {
        assertEquals(Freshness.LIVE, Freshness.fromAge(now - 119_000, now))
        assertEquals(Freshness.RECENT, Freshness.fromAge(now - 120_000, now))
        assertEquals(Freshness.RECENT, Freshness.fromAge(now - 899_000, now))
        assertEquals(Freshness.STALE, Freshness.fromAge(now - 900_000, now))
        assertEquals(Freshness.LIVE, Freshness.fromAge(now + 30_000, now)) // clock skew
    }

    @Test
    fun `server says live but the position has aged - never shown as live`() {
        assertEquals(Freshness.STALE, Freshness.effective("live", "2026-10-18T11:30:00Z", now))
        assertEquals(Freshness.RECENT, Freshness.effective("live", "2026-10-18T11:55:00Z", now))
    }

    @Test
    fun `server stale wins over a locally fresh timestamp`() {
        assertEquals(Freshness.STALE, Freshness.effective("stale", "2026-10-18T11:59:30Z", now))
    }

    @Test
    fun `unknown server value uses local rule, unparsable time is stale`() {
        assertEquals(Freshness.LIVE, Freshness.effective("whatever", "2026-10-18T11:59:30Z", now))
        assertEquals(Freshness.STALE, Freshness.effective("live", "not a time", now))
        assertEquals(Freshness.STALE, Freshness.effective("live", null, now))
    }
}

class TrackingStatusTest {
    @Test
    fun derive() {
        assertEquals(TrackingStatus.ON, TrackingStatus.derive(running = true, desired = true, pausedByUser = false))
        assertEquals(TrackingStatus.PAUSED, TrackingStatus.derive(running = false, desired = false, pausedByUser = true))
        assertEquals(TrackingStatus.PAUSED, TrackingStatus.derive(running = false, desired = true, pausedByUser = false))
        assertEquals(TrackingStatus.OFF, TrackingStatus.derive(running = false, desired = false, pausedByUser = false))
    }
}

class RelativeAgeTest {
    @Test
    fun buckets() {
        assertEquals(RelativeAge.JustNow, RelativeAge.of(0, 59_000))
        assertEquals(RelativeAge.Minutes(1), RelativeAge.of(0, 60_000))
        assertEquals(RelativeAge.Minutes(59), RelativeAge.of(0, 3_599_000))
        assertEquals(RelativeAge.Hours(2), RelativeAge.of(0, 7_200_000))
        assertEquals(RelativeAge.Days(3), RelativeAge.of(0, 3 * 86_400_000L))
        assertEquals(RelativeAge.JustNow, RelativeAge.of(10_000, 0)) // future
    }
}
