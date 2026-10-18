package org.familycircle.android.live

import org.familycircle.android.api.MapMember
import org.familycircle.android.api.MemberLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMapReducerTest {
    private fun loc(at: String, lat: Double = 47.0) = MemberLocation(
        lat = lat, lon = 8.0, accuracyM = 10.0, motion = "walking", recordedAt = at, receivedAt = at, freshness = "live",
    )

    private fun member(id: String, location: MemberLocation?, self: Boolean = false) =
        MapMember(userId = id, displayName = id, color = "#112233", hasAvatar = false, isSelf = self, location = location)

    private val base = LiveMapState(
        familyId = "f1",
        members = listOf(
            member("me", loc("2026-10-18T10:00:00Z"), self = true),
            member("anna", loc("2026-10-18T10:00:00Z")),
            member("bob", null),
        ),
    )

    private fun locationOf(state: LiveMapState, id: String) = state.members.first { it.userId == id }.location

    @Test
    fun `newer location replaces the old one`() {
        val r = LiveMapReducer.reduce(base, LiveEvent.Location("anna", loc("2026-10-18T10:01:00Z", lat = 48.0)))
        assertEquals(48.0, locationOf(r.state, "anna")!!.lat, 0.0)
        assertEquals(LiveEffect.NONE, r.effect)
    }

    @Test
    fun `older or equal location is ignored (out of order delivery)`() {
        val older = LiveMapReducer.reduce(base, LiveEvent.Location("anna", loc("2026-10-18T09:59:00Z", lat = 1.0)))
        assertSame(base, older.state)
        val same = LiveMapReducer.reduce(base, LiveEvent.Location("anna", loc("2026-10-18T10:00:00Z", lat = 1.0)))
        assertSame(base, same.state)
    }

    @Test
    fun `out of order sequence ends with the newest position`() {
        val events = listOf(
            LiveEvent.Location("anna", loc("2026-10-18T10:03:00Z", lat = 3.0)),
            LiveEvent.Location("anna", loc("2026-10-18T10:02:00Z", lat = 2.0)),
            LiveEvent.Location("anna", loc("2026-10-18T10:01:00Z", lat = 1.0)),
        )
        assertEquals(3.0, locationOf(LiveMapReducer.replay(base, events), "anna")!!.lat, 0.0)
    }

    @Test
    fun `different offsets are compared as instants`() {
        // 12:00:30+02:00 == 10:00:30Z, newer than 10:00:00Z.
        val r = LiveMapReducer.reduce(base, LiveEvent.Location("anna", loc("2026-10-18T12:00:30+02:00", lat = 5.0)))
        assertEquals(5.0, locationOf(r.state, "anna")!!.lat, 0.0)
    }

    @Test
    fun `location for a member without position sets it`() {
        val r = LiveMapReducer.reduce(base, LiveEvent.Location("bob", loc("2026-10-18T09:00:00Z")))
        assertEquals("2026-10-18T09:00:00Z", locationOf(r.state, "bob")!!.recordedAt)
    }

    @Test
    fun `unparsable recorded_at is ignored`() {
        val r = LiveMapReducer.reduce(base, LiveEvent.Location("anna", loc("yesterday")))
        assertSame(base, r.state)
    }

    @Test
    fun `location of an unknown user is ignored`() {
        val r = LiveMapReducer.reduce(base, LiveEvent.Location("stranger", loc("2026-10-18T11:00:00Z")))
        assertSame(base, r.state)
        assertEquals(LiveEffect.NONE, r.effect)
    }

    @Test
    fun `location_hidden removes the marker immediately`() {
        val r = LiveMapReducer.reduce(base, LiveEvent.LocationHidden("anna"))
        assertNull(locationOf(r.state, "anna"))
        // The member stays in the list.
        assertTrue(r.state.members.any { it.userId == "anna" })
    }

    @Test
    fun `hidden then an older location delivered late does not resurrect a newer-hidden marker beyond its time`() {
        // After hiding, any location event the server sends is by definition allowed again.
        val hidden = LiveMapReducer.reduce(base, LiveEvent.LocationHidden("anna")).state
        val back = LiveMapReducer.reduce(hidden, LiveEvent.Location("anna", loc("2026-10-18T10:05:00Z"))).state
        assertEquals("2026-10-18T10:05:00Z", locationOf(back, "anna")!!.recordedAt)
    }

    @Test
    fun `hidden for unknown user or member without location is a no-op`() {
        assertSame(base, LiveMapReducer.reduce(base, LiveEvent.LocationHidden("stranger")).state)
        assertSame(base, LiveMapReducer.reduce(base, LiveEvent.LocationHidden("bob")).state)
    }

    @Test
    fun `family_changed for the shown family refetches the map`() {
        val r = LiveMapReducer.reduce(base, LiveEvent.FamilyChanged("f1"))
        assertEquals(LiveEffect.REFETCH_MAP, r.effect)
        assertSame(base, r.state)
    }

    @Test
    fun `family_changed for another family refetches the family list`() {
        assertEquals(LiveEffect.REFETCH_FAMILIES, LiveMapReducer.reduce(base, LiveEvent.FamilyChanged("f2")).effect)
        assertEquals(LiveEffect.REFETCH_FAMILIES, LiveMapReducer.reduce(LiveMapState.EMPTY, LiveEvent.FamilyChanged("f1")).effect)
    }

    @Test
    fun `hello and unknown types change nothing`() {
        assertSame(base, LiveMapReducer.reduce(base, LiveEvent.Hello("me")).state)
        assertSame(base, LiveMapReducer.reduce(base, LiveEvent.Unknown("future_type")).state)
    }

    @Test
    fun `replay applies buffered events on top of a fetched snapshot`() {
        val events = listOf(LiveEvent.LocationHidden("anna"), LiveEvent.Location("bob", loc("2026-10-18T10:00:00Z")))
        val s = LiveMapReducer.replay(base, events)
        assertNull(locationOf(s, "anna"))
        assertEquals("2026-10-18T10:00:00Z", locationOf(s, "bob")!!.recordedAt)
    }
}

class LiveEventParseTest {
    @Test
    fun `parses all message types of the contract`() {
        assertEquals(LiveEvent.Hello("u1"), LiveEvent.parse("""{"type":"hello","user_id":"u1","server_time":"2026-10-18T10:00:00Z"}"""))
        assertEquals(LiveEvent.LocationHidden("u2"), LiveEvent.parse("""{"type":"location_hidden","user_id":"u2"}"""))
        assertEquals(LiveEvent.FamilyChanged("f1"), LiveEvent.parse("""{"type":"family_changed","family_id":"f1"}"""))
        val e = LiveEvent.parse(
            """{"type":"location","user_id":"u3","location":{"lat":47.1,"lon":8.2,"accuracy_m":12.5,"altitude_m":null,
            |"speed_mps":null,"bearing_deg":null,"battery_pct":80,"battery_charging":false,"motion":"walking",
            |"recorded_at":"2026-10-18T10:00:00Z","received_at":"2026-10-18T10:00:01Z","freshness":"live","extra":1}}""".trimMargin(),
        )
        assertTrue(e is LiveEvent.Location)
        e as LiveEvent.Location
        assertEquals("u3", e.userId)
        assertEquals(80, e.location.batteryPct)
        assertEquals(12.5, e.location.accuracyM, 0.0)
    }

    @Test
    fun `unknown type is forwarded as Unknown`() {
        assertEquals(LiveEvent.Unknown("chat"), LiveEvent.parse("""{"type":"chat","text":"hi"}"""))
    }

    @Test
    fun `malformed messages are dropped`() {
        assertNull(LiveEvent.parse("not json"))
        assertNull(LiveEvent.parse("[]"))
        assertNull(LiveEvent.parse("""{"user_id":"u1"}"""))
        assertNull(LiveEvent.parse("""{"type":"location","user_id":"u1"}"""))
        assertNull(LiveEvent.parse("""{"type":"location","user_id":"u1","location":{"lat":"x"}}"""))
        assertNull(LiveEvent.parse("""{"type":"location_hidden"}"""))
        assertNull(LiveEvent.parse("""{"type":"family_changed","family_id":""}"""))
        assertNull(LiveEvent.parse("""{"type":{"nested":true}}"""))
    }
}

class ReconnectBackoffTest {
    private val backoff = ReconnectBackoff(baseMs = 1_000, maxMs = 60_000)

    @Test
    fun `ceiling doubles per attempt and is capped`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (0..7).map(backoff::ceilingMs))
    }

    @Test
    fun `delay is between half the ceiling and the ceiling`() {
        for (attempt in 0..10) {
            val c = backoff.ceilingMs(attempt)
            assertEquals(c / 2, backoff.delayMs(attempt, 0.0))
            assertEquals(c, backoff.delayMs(attempt, 1.0))
            val mid = backoff.delayMs(attempt, 0.5)
            assertTrue(mid in c / 2..c)
        }
    }

    @Test
    fun `schedule with jitter 0_5`() {
        assertEquals(listOf(750L, 1_500L, 3_000L, 6_000L, 12_000L, 24_000L, 45_000L), (0..6).map { backoff.delayMs(it, 0.5) })
    }

    @Test
    fun `huge or negative attempts do not overflow`() {
        assertEquals(60_000L, backoff.ceilingMs(1_000_000))
        assertEquals(60_000L, backoff.ceilingMs(Int.MAX_VALUE))
        assertEquals(1_000L, backoff.ceilingMs(-5))
        assertTrue(backoff.delayMs(Int.MAX_VALUE, 0.99) <= 60_000L)
    }

    @Test
    fun `out of range random values are clamped`() {
        assertEquals(1_000L, backoff.delayMs(0, 7.0))
        assertEquals(500L, backoff.delayMs(0, -1.0))
    }
}
