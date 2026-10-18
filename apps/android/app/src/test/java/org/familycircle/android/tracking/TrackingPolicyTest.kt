package org.familycircle.android.tracking

import org.familycircle.android.tracking.TrackingPolicy.Companion.MINUTE
import org.familycircle.android.tracking.TrackingPolicy.Companion.SECOND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPolicyTest {
    private val policy = TrackingPolicy()
    private val now = 1_800_000_000_000L
    private val fullBattery = BatteryState(percent = 80, charging = false)

    @Test
    fun `saver targets 15 min, 250 m, low power`() {
        val p = policy.paramsFor(TrackingMode.SAVER, stationary = false)!!
        assertEquals(15 * MINUTE, p.intervalMs)
        assertEquals(250f, p.minDistanceM)
        assertEquals(LocationQuality.LOW_POWER, p.quality)
        assertTrue("heartbeat must fire before the next interval", p.heartbeatMs < p.intervalMs)
    }

    @Test
    fun `balanced targets 2 min, 50 m, balanced quality`() {
        val p = policy.paramsFor(TrackingMode.BALANCED, stationary = false)!!
        assertEquals(2 * MINUTE, p.intervalMs)
        assertEquals(50f, p.minDistanceM)
        assertEquals(LocationQuality.BALANCED, p.quality)
    }

    @Test
    fun `balanced relaxes interval while stationary`() {
        val moving = policy.paramsFor(TrackingMode.BALANCED, stationary = false)!!
        val still = policy.paramsFor(TrackingMode.BALANCED, stationary = true)!!
        assertTrue(still.intervalMs > moving.intervalMs)
        assertEquals(moving.minDistanceM, still.minDistanceM)
    }

    @Test
    fun `live targets 15 s, 10 m, high accuracy and ignores stationary`() {
        val p = policy.paramsFor(TrackingMode.LIVE, stationary = false)!!
        assertEquals(15 * SECOND, p.intervalMs)
        assertEquals(10f, p.minDistanceM)
        assertEquals(LocationQuality.HIGH_ACCURACY, p.quality)
        assertEquals(p, policy.paramsFor(TrackingMode.LIVE, stationary = true))
    }

    @Test
    fun `off has no parameters`() {
        assertNull(policy.paramsFor(TrackingMode.OFF, stationary = false))
        assertEquals(TrackingMode.OFF, policy.effectiveMode(TrackingMode.OFF, fullBattery, null, now))
    }

    @Test
    fun `heartbeat keeps stationary users from turning stale`() {
        // Server: live < 2 min, recent < 15 min.
        assertTrue(policy.paramsFor(TrackingMode.LIVE, true)!!.heartbeatMs < 2 * MINUTE)
        assertTrue(policy.paramsFor(TrackingMode.BALANCED, true)!!.heartbeatMs < 15 * MINUTE)
        assertTrue(policy.paramsFor(TrackingMode.SAVER, true)!!.heartbeatMs < 15 * MINUTE)
    }

    @Test
    fun `live stays live within 30 minutes`() {
        val started = now - 29 * MINUTE
        assertEquals(TrackingMode.LIVE, policy.effectiveMode(TrackingMode.LIVE, fullBattery, started, now))
        assertFalse(policy.isLiveExpired(started, now))
        assertEquals(1 * MINUTE, policy.liveRemainingMs(started, now))
    }

    @Test
    fun `live falls back to balanced after 30 minutes`() {
        val started = now - 30 * MINUTE
        assertTrue(policy.isLiveExpired(started, now))
        assertEquals(TrackingMode.BALANCED, policy.effectiveMode(TrackingMode.LIVE, fullBattery, started, now))
        assertEquals(0, policy.liveRemainingMs(started, now))
    }

    @Test
    fun `live without or with future start time is treated as expired`() {
        assertEquals(TrackingMode.BALANCED, policy.effectiveMode(TrackingMode.LIVE, fullBattery, null, now))
        assertEquals(TrackingMode.BALANCED, policy.effectiveMode(TrackingMode.LIVE, fullBattery, now + MINUTE, now))
    }

    @Test
    fun `low battery steps down one level`() {
        val low = BatteryState(percent = 15, charging = false)
        val started = now - MINUTE
        assertEquals(TrackingMode.BALANCED, policy.effectiveMode(TrackingMode.LIVE, low, started, now))
        assertEquals(TrackingMode.SAVER, policy.effectiveMode(TrackingMode.BALANCED, low, null, now))
    }

    @Test
    fun `low battery never goes below saver`() {
        val low = BatteryState(percent = 3, charging = false)
        assertEquals(TrackingMode.SAVER, policy.effectiveMode(TrackingMode.SAVER, low, null, now))
    }

    @Test
    fun `expired live plus low battery ends in saver`() {
        val low = BatteryState(percent = 10, charging = false)
        assertEquals(TrackingMode.SAVER, policy.effectiveMode(TrackingMode.LIVE, low, now - 31 * MINUTE, now))
    }

    @Test
    fun `charging or unknown battery does not step down`() {
        assertEquals(TrackingMode.BALANCED, policy.effectiveMode(TrackingMode.BALANCED, BatteryState(5, charging = true), null, now))
        assertEquals(TrackingMode.BALANCED, policy.effectiveMode(TrackingMode.BALANCED, BatteryState(null, charging = false), null, now))
        assertEquals(TrackingMode.BALANCED, policy.effectiveMode(TrackingMode.BALANCED, null, null, now))
        assertEquals(TrackingMode.BALANCED, policy.effectiveMode(TrackingMode.BALANCED, BatteryState(16, charging = false), null, now))
    }
}
