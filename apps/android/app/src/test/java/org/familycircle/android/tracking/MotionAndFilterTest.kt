package org.familycircle.android.tracking

import org.familycircle.android.data.queue.PendingLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionClassifierTest {
    private val classifier = MotionClassifier()

    private fun sample(t: Long, lat: Double = 47.0, lon: Double = 8.0, acc: Float = 10f, speed: Float? = null) =
        MotionClassifier.Sample(t, lat, lon, acc, speed)

    @Test
    fun `uses provider speed when present`() {
        assertEquals(Motion.STATIONARY, classifier.classify(sample(0, speed = 0.2f), null))
        assertEquals(Motion.WALKING, classifier.classify(sample(0, speed = 1.4f), null))
        assertEquals(Motion.DRIVING, classifier.classify(sample(0, speed = 13.9f), null))
    }

    @Test
    fun `thresholds are exclusive upper bounds`() {
        assertEquals(Motion.WALKING, classifier.classify(sample(0, speed = 0.6f), null))
        assertEquals(Motion.DRIVING, classifier.classify(sample(0, speed = 2.8f), null))
    }

    @Test
    fun `unknown without speed and without previous fix`() {
        assertEquals(Motion.UNKNOWN, classifier.classify(sample(0), null))
    }

    @Test
    fun `derives speed from displacement`() {
        // ~111 m north in 60 s ≈ 1.85 m/s → walking
        val prev = sample(0, lat = 47.0)
        val cur = sample(60_000, lat = 47.001)
        assertEquals(Motion.WALKING, classifier.classify(cur, prev))
        // ~1.1 km in 60 s ≈ 18.5 m/s → driving
        assertEquals(Motion.DRIVING, classifier.classify(sample(60_000, lat = 47.01), prev))
    }

    @Test
    fun `displacement within accuracy counts as stationary`() {
        val prev = sample(0, lat = 47.0, acc = 50f)
        val cur = sample(60_000, lat = 47.0003, acc = 50f) // ~33 m < 50 m accuracy
        assertEquals(Motion.STATIONARY, classifier.classify(cur, prev))
    }

    @Test
    fun `no estimate when fixes are too close or too far apart in time`() {
        val prev = sample(0, lat = 47.0)
        assertNull(classifier.estimateSpeed(sample(2_000, lat = 47.001), prev))
        assertNull(classifier.estimateSpeed(sample(31 * 60_000L, lat = 47.001), prev))
    }

    @Test
    fun `negative or NaN provider speed falls back to displacement`() {
        val prev = sample(0, lat = 47.0)
        assertEquals(Motion.WALKING, classifier.classify(sample(60_000, lat = 47.001, speed = -1f), prev))
        assertEquals(Motion.UNKNOWN, classifier.classify(sample(60_000, speed = Float.NaN), null))
    }
}

class FixFilterTest {
    private fun fix(t: Long, acc: Float? = 10f, lat: Double = 47.0, lon: Double = 8.0) = Fix(t, lat, lon, acc)

    @Test
    fun `accepts a normal fix`() {
        assertNull(FixFilter().accept(fix(1_000)))
    }

    @Test
    fun `drops fixes without accuracy or with zero accuracy`() {
        assertEquals(FixFilter.Rejection.NO_ACCURACY, FixFilter().check(fix(1_000, acc = null)))
        assertEquals(FixFilter.Rejection.NO_ACCURACY, FixFilter().check(fix(1_000, acc = 0f)))
        assertEquals(FixFilter.Rejection.NO_ACCURACY, FixFilter().check(fix(1_000, acc = Float.NaN)))
    }

    @Test
    fun `drops fixes worse than 2000 m`() {
        assertNull(FixFilter().check(fix(1_000, acc = 2000f)))
        assertEquals(FixFilter.Rejection.INACCURATE, FixFilter().check(fix(1_000, acc = 2000.5f)))
    }

    @Test
    fun `drops fixes older than or equal to the last accepted`() {
        val filter = FixFilter()
        assertNull(filter.accept(fix(5_000)))
        assertEquals(FixFilter.Rejection.NOT_NEWER, filter.accept(fix(5_000)))
        assertEquals(FixFilter.Rejection.NOT_NEWER, filter.accept(fix(4_999)))
        assertNull(filter.accept(fix(5_001)))
    }

    @Test
    fun `restores last accepted time`() {
        val filter = FixFilter(lastAcceptedTimeMs = 10_000)
        assertEquals(FixFilter.Rejection.NOT_NEWER, filter.check(fix(9_000)))
    }

    @Test
    fun `drops invalid coordinates and time`() {
        assertEquals(FixFilter.Rejection.INVALID, FixFilter().check(fix(1_000, lat = 91.0)))
        assertEquals(FixFilter.Rejection.INVALID, FixFilter().check(fix(1_000, lon = Double.NaN)))
        assertEquals(FixFilter.Rejection.INVALID, FixFilter().check(fix(0)))
    }

    @Test
    fun `check does not change state`() {
        val filter = FixFilter()
        filter.check(fix(5_000))
        assertNull(filter.lastAcceptedTimeMs)
    }
}

class FixProcessorTest {
    private val params = TrackingPolicy().paramsFor(TrackingMode.BALANCED, stationary = false)!!

    @Test
    fun `records first fix, thins close fixes, records after distance or heartbeat`() {
        val p = FixProcessor()
        assertTrue(p.process(Fix(1_000, 47.0, 8.0, 10f), params) is FixProcessor.Result.Record)
        // 10 s later, ~11 m away: below 50 m and before the heartbeat → dropped
        assertTrue(p.process(Fix(11_000, 47.0001, 8.0, 10f), params) is FixProcessor.Result.Drop)
        // ~111 m away → recorded
        assertTrue(p.process(Fix(21_000, 47.001, 8.0, 10f), params) is FixProcessor.Result.Record)
        // same place, but heartbeat (5 min) elapsed → recorded
        assertTrue(p.process(Fix(21_000 + params.heartbeatMs, 47.001, 8.0, 10f), params) is FixProcessor.Result.Record)
    }

    @Test
    fun `one-shot bypasses the gate but not the filter`() {
        val p = FixProcessor()
        assertTrue(p.process(Fix(1_000, 47.0, 8.0, 10f), params) is FixProcessor.Result.Record)
        assertTrue(p.process(Fix(2_000, 47.0, 8.0, 10f), null) is FixProcessor.Result.Record)
        assertTrue(p.process(Fix(2_000, 47.0, 8.0, 10f), null) is FixProcessor.Result.Drop)
        assertTrue(p.process(Fix(3_000, 47.0, 8.0, null), null) is FixProcessor.Result.Drop)
    }

    @Test
    fun `gate-dropped fix does not advance the filter time`() {
        val p = FixProcessor()
        p.process(Fix(1_000, 47.0, 8.0, 10f), params)
        assertTrue(p.process(Fix(5_000, 47.0, 8.0, 10f), params) is FixProcessor.Result.Drop)
        // A one-shot with an older time than the dropped fix but newer than the recorded one is fine.
        assertTrue(p.process(Fix(3_000, 47.0, 8.0, 10f), null) is FixProcessor.Result.Record)
    }
}

class PendingLocationFactoryTest {
    @Test
    fun `drops out-of-range optional values and keeps the fix time`() {
        val fix = Fix(1_234, 47.0, 8.0, 12f, altitudeM = 50_000.0, speedMps = -1f, bearingDeg = 360f)
        val p = PendingLocation.from(fix, Motion.WALKING, BatteryState(101, true), createdAtMs = 9_999, id = "x")
        assertEquals(1_234, p.recordedAtMs)
        assertNull(p.altitudeM)
        assertNull(p.speedMps)
        assertNull(p.bearingDeg)
        assertNull(p.batteryPct)
        assertEquals("walking", p.motion)
    }

    @Test
    fun `battery is omitted when not shared`() {
        val p = PendingLocation.from(Fix(1, 0.0, 0.0, 5f), Motion.UNKNOWN, battery = null, createdAtMs = 1)
        assertNull(p.batteryPct)
        assertNull(p.batteryCharging)
    }

    @Test
    fun `valid optional values are kept`() {
        val fix = Fix(1, 0.0, 0.0, 5f, altitudeM = 420.0, speedMps = 1.5f, bearingDeg = 359.9f)
        val p = PendingLocation.from(fix, Motion.WALKING, BatteryState(55, false), createdAtMs = 1)
        assertEquals(420.0, p.altitudeM!!, 0.0)
        assertEquals(1.5f, p.speedMps!!, 0f)
        assertEquals(359.9f, p.bearingDeg!!, 0f)
        assertEquals(55, p.batteryPct)
        assertEquals(false, p.batteryCharging)
    }
}

class ProviderSelectorTest {
    @Test
    fun `fused alone only for low power`() {
        val all = setOf("fused", "gps", "network", "passive")
        assertEquals(listOf("fused"), ProviderSelector.select(all, LocationQuality.LOW_POWER))
    }

    @Test
    fun `balanced and high accuracy add gps to fused so positions never stop when network location is unavailable`() {
        val all = setOf("fused", "gps", "network", "passive")
        assertEquals(listOf("fused", "gps"), ProviderSelector.select(all, LocationQuality.BALANCED))
        assertEquals(listOf("fused", "gps"), ProviderSelector.select(all, LocationQuality.HIGH_ACCURACY))
        // Device without a GPS chip: fused only.
        assertEquals(listOf("fused"), ProviderSelector.select(setOf("fused", "network"), LocationQuality.BALANCED))
    }

    @Test
    fun `without fused uses gps and network`() {
        val all = setOf("gps", "network", "passive")
        assertEquals(listOf("gps", "network"), ProviderSelector.select(all, LocationQuality.BALANCED))
        assertEquals(listOf("network", "passive"), ProviderSelector.select(all, LocationQuality.LOW_POWER))
    }

    @Test
    fun `low power without network provider falls back to gps`() {
        assertEquals(listOf("gps", "passive"), ProviderSelector.select(setOf("gps", "passive"), LocationQuality.LOW_POWER))
    }

    @Test
    fun `nothing available yields empty list`() {
        assertTrue(ProviderSelector.select(emptySet(), LocationQuality.BALANCED).isEmpty())
        assertNull(ProviderSelector.selectOneShot(setOf("passive")))
        assertEquals("gps", ProviderSelector.selectOneShot(setOf("gps", "network")))
    }
}

/**
 * Regression found in the two-emulator test: with fused + GPS requested together, the fused provider
 * re-delivered an old position with a fresh timestamp between fresh GPS fixes, which made the real GPS
 * fix look like an impossible jump on the server.
 */
class ProviderPriorityTest {
    private val balanced = LocationParams(TrackingMode.BALANCED, 120_000, 30_000, 0f, 300_000, LocationQuality.BALANCED)

    private fun fix(t: Long, lat: Double, provider: String) =
        Fix(timeMs = t, lat = lat, lon = 8.54, accuracyM = 5f, provider = provider)

    @Test
    fun `non-gps fixes are dropped while gps is delivering`() {
        val p = FixProcessor()
        assertTrue(p.process(fix(1_000, 47.3834, "gps"), balanced) is FixProcessor.Result.Record)
        val stale = p.process(fix(3_000, 47.3797, "fused"), balanced)
        assertEquals(FixProcessor.Result.Drop("filter:SUPERSEDED_BY_GPS"), stale)
    }

    @Test
    fun `fused takes over when gps has been silent for two intervals`() {
        val p = FixProcessor()
        p.process(fix(1_000, 47.3834, "gps"), balanced)
        val later = 1_000 + 2 * balanced.intervalMs + 1
        assertTrue(p.process(fix(later, 47.3840, "fused"), balanced) is FixProcessor.Result.Record)
    }

    @Test
    fun `without any gps fix other providers are used normally`() {
        val p = FixProcessor()
        assertTrue(p.process(fix(1_000, 47.0, "fused"), balanced) is FixProcessor.Result.Record)
        assertTrue(p.process(fix(200_000, 47.01, "network"), balanced) is FixProcessor.Result.Record)
    }
}

/** Regression from the emulator test: the provider reported speed 0 while the device kept moving. */
class MotionSpeedConsistencyTest {
    private val c = MotionClassifier()

    @Test
    fun `displacement wins over a zero provider speed when the move exceeds the accuracy`() {
        val prev = MotionClassifier.Sample(0, 47.3728, 8.542, 5f, 0f)
        val cur = MotionClassifier.Sample(10_000, 47.3726, 8.542, 5f, 0f) // ~22 m in 10 s
        assertEquals(Motion.WALKING, c.classify(cur, prev))
    }

    @Test
    fun `provider speed is kept when the displacement is within the accuracy`() {
        val prev = MotionClassifier.Sample(0, 47.3728, 8.542, 30f, null)
        val cur = MotionClassifier.Sample(10_000, 47.37281, 8.542, 30f, 1.5f)
        assertEquals(Motion.WALKING, c.classify(cur, prev))
        val still = MotionClassifier.Sample(10_000, 47.37281, 8.542, 30f, 0f)
        assertEquals(Motion.STATIONARY, c.classify(still, prev))
    }
}
