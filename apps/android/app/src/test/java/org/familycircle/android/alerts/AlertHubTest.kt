package org.familycircle.android.alerts

import kotlinx.coroutines.runBlocking
import org.familycircle.android.api.Alert
import org.familycircle.android.api.AlertActor
import org.familycircle.android.api.AlertKind
import org.familycircle.android.api.AlertsResponse
import org.familycircle.android.api.ApiException
import org.familycircle.android.util.Rfc3339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AlertHubTest {
    private val now = Rfc3339.parseMillis("2026-10-18T12:00:00Z")!!

    private fun alert(id: Long, kind: String, active: Boolean = false, mine: Boolean = false, actor: String = "anna") = Alert(
        id = id,
        kind = kind,
        actor = AlertActor(actor, actor),
        createdAt = "2026-10-18T11:59:00Z",
        active = active,
        mine = mine,
    )

    private class Fake(pages: List<List<Alert>>) {
        val queue = ArrayDeque(pages)
        val calls = mutableListOf<Pair<Long, Int>>()
        var fail = false

        suspend fun fetch(after: Long, limit: Int): AlertsResponse {
            calls += after to limit
            if (fail) throw ApiException.Network(IOException("offline"))
            return AlertsResponse(queue.removeFirstOrNull().orEmpty(), "2026-10-18T12:00:00Z")
        }
    }

    private fun hub(fake: Fake, store: AlertStateStore, effects: MutableList<AlertEffect>) =
        AlertHub(fake::fetch, store, { effects += it }, ownDeviceId = { "me-phone" }, clock = { now })

    @Test
    fun `first catch-up reads everything from the start but only alarms active sos`() = runBlocking {
        val page1 = (1L..AlertHub.PAGE).map { alert(it, AlertKind.CHECK_IN) }
        val page2 = listOf(alert(101, AlertKind.SOS, active = true, actor = "bob"), alert(102, AlertKind.ARRIVAL))
        val fake = Fake(listOf(page1, page2))
        val store = InMemoryAlertStateStore()
        val effects = mutableListOf<AlertEffect>()

        assertTrue(hub(fake, store, effects).catchUp())

        assertEquals(listOf(0L to AlertHub.PAGE, 100L to AlertHub.PAGE), fake.calls)
        assertEquals(listOf(AlertEffect.Sos::class), effects.map { it::class })
        val saved = store.load()
        assertTrue(saved.initialized)
        assertEquals(102L, saved.lastId)
        assertEquals("bob", saved.activeSos.single().actor.userId)
    }

    @Test
    fun `later catch-ups continue at the cursor and announce new alerts`() = runBlocking {
        val store = InMemoryAlertStateStore(AlertState(lastId = 50, initialized = true))
        val fake = Fake(listOf(listOf(alert(51, AlertKind.CHECK_IN), alert(52, AlertKind.RING, active = true))))
        val effects = mutableListOf<AlertEffect>()

        hub(fake, store, effects).catchUp()

        assertEquals(50L, fake.calls.single().first)
        assertEquals(listOf(AlertEffect.CheckIn::class, AlertEffect.Ring::class), effects.map { it::class })
        assertEquals(52L, store.load().lastId)
    }

    @Test
    fun `an alert received live is not handled again by the catch-up`() = runBlocking {
        val store = InMemoryAlertStateStore(AlertState(lastId = 10, initialized = true))
        val fake = Fake(listOf(listOf(alert(12, AlertKind.ARRIVAL))))
        val effects = mutableListOf<AlertEffect>()
        val hub = hub(fake, store, effects)

        hub.receive(listOf(alert(12, AlertKind.ARRIVAL)))
        // The catch-up started before 12 arrived would still use after=10 – here it reads after=12.
        hub.catchUp()

        assertEquals(listOf(12L), effects.map { it.alert.id })
        assertEquals(12L, fake.calls.single().first)
    }

    @Test
    fun `out-of-order delivery during a catch-up is deduplicated`() = runBlocking {
        val store = InMemoryAlertStateStore(AlertState(lastId = 10, initialized = true))
        val effects = mutableListOf<AlertEffect>()
        val fake = Fake(listOf(listOf(alert(11, AlertKind.CHECK_IN), alert(12, AlertKind.ARRIVAL))))
        val hub = hub(fake, store, effects)
        hub.receive(listOf(alert(12, AlertKind.ARRIVAL)))
        // Simulates a catch-up that read the old cursor: 11 is new, 12 is a duplicate.
        hub.receive(listOf(alert(11, AlertKind.CHECK_IN), alert(12, AlertKind.ARRIVAL)))
        assertEquals(listOf(12L, 11L), effects.map { it.alert.id })
    }

    @Test
    fun `failed catch-up reports false and keeps the state`() = runBlocking {
        val store = InMemoryAlertStateStore(AlertState(lastId = 3, initialized = true))
        val fake = Fake(emptyList()).apply { fail = true }
        val effects = mutableListOf<AlertEffect>()
        assertFalse(hub(fake, store, effects).catchUp())
        assertEquals(3L, store.load().lastId)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `own sos state follows send and resolve and survives a restart`() = runBlocking {
        val store = InMemoryAlertStateStore(AlertState(initialized = true))
        val effects = mutableListOf<AlertEffect>()
        val fake = Fake(emptyList())
        val first = hub(fake, store, effects)
        first.ownSosSent(alert(5, AlertKind.SOS, active = true, actor = "me"))
        assertEquals(5L, first.sos.value.own!!.id)

        val restarted = hub(fake, store, effects)
        assertEquals(5L, restarted.sos.value.own!!.id)
        // The live echo of my own SOS (mine=true) changes nothing.
        restarted.receive(listOf(alert(5, AlertKind.SOS, active = true, mine = true, actor = "me")))
        restarted.ownSosResolved()
        assertNull(restarted.sos.value.own)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `reset forgets everything`() = runBlocking {
        val store = InMemoryAlertStateStore(AlertState(lastId = 9, initialized = true, activeSos = listOf(alert(9, AlertKind.SOS, active = true))))
        val hub = hub(Fake(emptyList()), store, mutableListOf())
        assertEquals(1, hub.sos.value.others.size)
        hub.reset()
        assertEquals(AlertState(), store.load())
        assertTrue(hub.sos.value.others.isEmpty())
    }
}
