package org.familycircle.android.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryGateTest {
    private fun LowBatteryGate.feed(vararg readings: Pair<Int, Boolean>): List<Boolean> = readings.map { (pct, charging) -> onReading(pct, charging) }

    @Test
    fun `fires once when the level falls to 15 percent while discharging`() {
        val gate = LowBatteryGate()
        assertEquals(listOf(false, false, true, false, false), gate.feed(17 to false, 16 to false, 15 to false, 14 to false, 10 to false))
    }

    @Test
    fun `a big drop across the threshold also counts`() {
        val gate = LowBatteryGate()
        assertEquals(listOf(false, true), gate.feed(50 to false, 10 to false))
    }

    @Test
    fun `already low at the first reading is not a crossing`() {
        val gate = LowBatteryGate()
        assertEquals(listOf(false, false), gate.feed(12 to false, 11 to false))
    }

    @Test
    fun `charging starts a new discharge cycle`() {
        val gate = LowBatteryGate()
        assertTrue(gate.feed(16 to false, 15 to false).last())
        assertFalse(gate.onReading(15, charging = true))
        assertFalse(gate.onReading(30, charging = true))
        assertEquals(listOf(false, true), gate.feed(16 to false, 15 to false))
    }

    @Test
    fun `never fires while charging`() {
        val gate = LowBatteryGate()
        assertEquals(listOf(false, false), gate.feed(20 to true, 10 to true))
    }

    @Test
    fun `jitter around the threshold does not fire twice`() {
        val gate = LowBatteryGate()
        assertEquals(listOf(false, true, false, false, false), gate.feed(16 to false, 15 to false, 16 to false, 15 to false, 17 to false))
        // Clearly above again (without a charging event seen): new cycle.
        assertEquals(listOf(false, true), gate.feed(25 to false, 15 to false))
    }
}
