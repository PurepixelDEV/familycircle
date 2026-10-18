package org.familycircle.android.alerts

import org.familycircle.android.api.Alert
import org.familycircle.android.api.AlertActor
import org.familycircle.android.api.AlertKind
import org.familycircle.android.api.AlertPoint
import org.familycircle.android.util.Rfc3339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertProcessorTest {
    private val now = Rfc3339.parseMillis("2026-10-18T12:00:00Z")!!
    private val ctx = AlertContext(nowMs = now, ownDeviceId = "me-phone")

    private fun alert(
        id: Long,
        kind: String,
        actor: String = "anna",
        active: Boolean = false,
        mine: Boolean = false,
        fromDevice: String? = null,
        createdAt: String = "2026-10-18T11:59:00Z",
        location: AlertPoint? = AlertPoint(47.0, 8.0, 10.0),
    ) = Alert(
        id = id,
        kind = kind,
        actor = AlertActor(actor, actor.replaceFirstChar { it.uppercase() }),
        fromDeviceId = fromDevice,
        location = location,
        createdAt = createdAt,
        active = active,
        mine = mine,
    )

    private fun process(vararg alerts: Alert, state: AlertState = AlertState(), context: AlertContext = ctx) =
        AlertProcessor.process(state, alerts.toList(), context)

    @Test
    fun `each alert is handled once and the cursor only grows`() {
        val first = process(alert(5, AlertKind.CHECK_IN), alert(3, AlertKind.ARRIVAL))
        assertEquals(5L, first.state.lastId)
        assertEquals(listOf(3L, 5L), first.effects.map { it.alert.id }) // oldest first
        val again = AlertProcessor.process(first.state, listOf(alert(5, AlertKind.CHECK_IN), alert(4, AlertKind.DEPARTURE)), ctx)
        assertEquals(listOf(4L), again.effects.map { it.alert.id })
        assertEquals(5L, again.state.lastId)
    }

    @Test
    fun `recent ids are bounded`() {
        val many = (1L..(AlertProcessor.MAX_RECENT + 50)).map { alert(it, "unknown_kind") }
        val result = AlertProcessor.process(AlertState(), many, ctx)
        assertEquals(AlertProcessor.MAX_RECENT, result.state.recentIds.size)
        assertEquals(AlertProcessor.MAX_RECENT + 50L, result.state.lastId)
    }

    @Test
    fun `active sos of someone else alarms and is shown on the board`() {
        val result = process(alert(1, AlertKind.SOS, active = true))
        assertTrue(result.effects.single() is AlertEffect.Sos)
        assertEquals("anna", result.state.activeSos.single().actor.userId)
    }

    @Test
    fun `sos resolved closes the alarm and says the person is safe`() {
        val withSos = process(alert(1, AlertKind.SOS, active = true)).state
        val result = process(alert(2, AlertKind.SOS_RESOLVED), state = withSos)
        val effect = result.effects.single() as AlertEffect.SosEnded
        assertTrue(effect.notify)
        assertTrue(result.state.activeSos.isEmpty())
    }

    @Test
    fun `an sos listed as inactive closes a notification shown before`() {
        val withSos = process(alert(1, AlertKind.SOS, active = true)).state
        // Same id again is a duplicate; a newer listing of an inactive SOS (e.g. other id) is not.
        val result = process(alert(1, AlertKind.SOS, active = false), state = withSos.copy(recentIds = emptyList()))
        assertEquals(AlertEffect.SosEnded(alert(1, AlertKind.SOS, active = false), notify = false), result.effects.single())
        assertTrue(result.state.activeSos.isEmpty())
    }

    @Test
    fun `own sos from another device only sets the own state`() {
        val active = process(alert(1, AlertKind.SOS, actor = "me", active = true, mine = true))
        assertTrue(active.effects.isEmpty())
        assertEquals(1L, active.state.ownSos!!.id)
        val resolved = process(alert(2, AlertKind.SOS_RESOLVED, actor = "me", mine = true), state = active.state)
        assertTrue(resolved.effects.isEmpty())
        assertNull(resolved.state.ownSos)
    }

    @Test
    fun `own check-in is not announced`() {
        assertTrue(process(alert(1, AlertKind.CHECK_IN, actor = "me", mine = true)).effects.isEmpty())
    }

    @Test
    fun `ring only while active and not from this phone`() {
        assertTrue(process(alert(1, AlertKind.RING, active = true, fromDevice = "anna-phone")).effects.single() is AlertEffect.Ring)
        assertTrue(process(alert(1, AlertKind.RING, active = false, fromDevice = "anna-phone")).effects.isEmpty())
        assertTrue(process(alert(1, AlertKind.RING, actor = "me", active = true, fromDevice = "me-phone")).effects.isEmpty())
        // My other device asking this phone to ring.
        assertTrue(process(alert(1, AlertKind.RING, actor = "me", active = true, fromDevice = "me-tablet")).effects.single() is AlertEffect.Ring)
    }

    @Test
    fun `locate only while active and not from this phone`() {
        assertTrue(process(alert(1, AlertKind.LOCATE, active = true, fromDevice = "x")).effects.single() is AlertEffect.Locate)
        assertTrue(process(alert(1, AlertKind.LOCATE, active = false)).effects.isEmpty())
        assertTrue(process(alert(1, AlertKind.LOCATE, active = true, fromDevice = "me-phone")).effects.isEmpty())
    }

    @Test
    fun `informational kinds map to their notifications`() {
        val effects = process(
            alert(1, AlertKind.CHECK_IN),
            alert(2, AlertKind.ARRIVAL),
            alert(3, AlertKind.DEPARTURE),
            alert(4, AlertKind.BATTERY_LOW),
            alert(5, AlertKind.LAST_LOCATION),
        ).effects
        assertTrue(effects[0] is AlertEffect.CheckIn)
        assertEquals(true, (effects[1] as AlertEffect.Place).arrived)
        assertEquals(false, (effects[2] as AlertEffect.Place).arrived)
        assertTrue(effects[3] is AlertEffect.BatteryLow)
        assertTrue(effects[4] is AlertEffect.LastLocation)
    }

    @Test
    fun `old informational alerts are not announced, an old active sos is`() {
        val old = "2026-10-16T12:00:00Z"
        val result = process(
            alert(1, AlertKind.ARRIVAL, createdAt = old),
            alert(2, AlertKind.CHECK_IN, createdAt = old),
            alert(3, AlertKind.SOS, active = true, createdAt = "2026-10-17T13:00:00Z"),
        )
        assertTrue(result.effects.single() is AlertEffect.Sos)
        assertEquals(3L, result.state.lastId)
    }

    @Test
    fun `first catch-up after sign-in announces only active sos`() {
        val initial = ctx.copy(initial = true)
        val result = process(
            alert(1, AlertKind.CHECK_IN),
            alert(2, AlertKind.SOS, actor = "bob", active = true),
            alert(3, AlertKind.RING, active = true),
            alert(4, AlertKind.SOS_RESOLVED, actor = "clara"),
            context = initial,
        )
        assertEquals(listOf(AlertEffect.Sos::class, AlertEffect.SosEnded::class), result.effects.map { it::class })
        assertEquals(false, (result.effects[1] as AlertEffect.SosEnded).notify)
    }

    @Test
    fun `sos older than 24 hours expires from the board`() {
        val state = AlertState(
            activeSos = listOf(alert(1, AlertKind.SOS, active = true, createdAt = "2026-10-17T11:00:00Z")),
            ownSos = alert(2, AlertKind.SOS, actor = "me", active = true, mine = true, createdAt = "2026-10-17T11:30:00Z"),
        )
        val expired = AlertProcessor.expire(state, now)
        assertTrue(expired.activeSos.isEmpty())
        assertNull(expired.ownSos)
    }

    @Test
    fun `a newer sos of the same person replaces the older one`() {
        val first = process(alert(1, AlertKind.SOS, active = true)).state
        val second = process(alert(5, AlertKind.SOS, active = true), state = first)
        assertEquals(5L, second.state.activeSos.single().id)
    }

    @Test
    fun `own sos sent from this phone is kept as mine`() {
        val state = AlertProcessor.ownSosSent(AlertState(), alert(9, AlertKind.SOS, actor = "me", active = true, mine = false))
        assertTrue(state.ownSos!!.mine)
        assertNull(AlertProcessor.ownSosResolved(state).ownSos)
    }

    @Test
    fun `unknown kinds are ignored but advance the cursor`() {
        val result = process(alert(7, "teleport"))
        assertTrue(result.effects.isEmpty())
        assertEquals(7L, result.state.lastId)
    }
}
