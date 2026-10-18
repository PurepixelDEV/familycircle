package org.familycircle.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.familycircle.android.api.Alert
import org.familycircle.android.api.AlertActor
import org.familycircle.android.api.AlertPoint
import org.familycircle.android.api.HistoryPoint
import org.familycircle.android.api.PlaceInput
import org.familycircle.android.live.LiveEvent
import org.familycircle.android.live.LiveMapReducer
import org.familycircle.android.live.LiveMapState
import org.familycircle.android.live.LiveEffect
import org.familycircle.android.map.MapGeoJson
import org.familycircle.android.map.PlaceShape
import org.familycircle.android.map.SosPin
import org.familycircle.android.ui.family.RadiusScale
import org.familycircle.android.ui.history.HistoryModel
import org.familycircle.android.util.Geo
import org.familycircle.android.util.Rfc3339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class Phase3ModelTest {
    // ------------------------------------------------------------------ live events

    @Test
    fun `alert and places_changed messages are parsed`() {
        val alert = LiveEvent.parse(
            """{"type":"alert","alert":{"id":3,"kind":"ring","actor":{"user_id":"u1","display_name":"Anna"},"from_device_id":"d1",
            "place":null,"location":null,"battery_pct":null,"message":null,"created_at":"2026-10-18T10:00:00Z",
            "expires_at":"2026-10-18T10:02:00Z","resolved_at":null,"active":true,"mine":false}}""",
        ) as LiveEvent.AlertReceived
        assertEquals(3L, alert.alert.id)
        assertEquals("d1", alert.alert.fromDeviceId)
        assertTrue(alert.alert.active)
        assertEquals(LiveEvent.PlacesChanged("f1"), LiveEvent.parse("""{"type":"places_changed","family_id":"f1"}"""))
        assertNull(LiveEvent.parse("""{"type":"alert","alert":{"id":"x"}}"""))
        assertNull(LiveEvent.parse("""{"type":"places_changed"}"""))
    }

    @Test
    fun `alerts and place changes do not touch the map members`() {
        val state = LiveMapState("f1", emptyList())
        assertEquals(LiveEffect.NONE, LiveMapReducer.reduce(state, LiveEvent.PlacesChanged("f1")).effect)
    }

    // ------------------------------------------------------------------ map overlays

    @Test
    fun `places become closed circles of their radius plus label points`() {
        val place = PlaceShape("p1", "Schule", 47.0, 8.0, 200)
        val areas = Json.parseToJsonElement(MapGeoJson.placeAreas(listOf(place))).jsonObject
        val feature = areas["features"]!!.jsonArray.single().jsonObject
        assertEquals("Polygon", feature["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val ring = feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals(ring.first(), ring.last())
        val (lon, lat) = ring[5].jsonArray.map { it.jsonPrimitive.content.toDouble() }
        assertEquals(200.0, Geo.distanceMeters(47.0, 8.0, lat, lon), 3.0)

        val labels = Json.parseToJsonElement(MapGeoJson.placeLabels(listOf(place)) { "img-" + it.id }).jsonObject
        val label = labels["features"]!!.jsonArray.single().jsonObject["properties"]!!.jsonObject
        assertEquals("img-p1", label[MapGeoJson.PROP_ICON]!!.jsonPrimitive.content)
    }

    @Test
    fun `sos pins need a position`() {
        val withPos = Alert(1, "sos", AlertActor("u1", "Anna"), location = AlertPoint(47.0, 8.0), createdAt = "2026-10-18T10:00:00Z")
        assertEquals(SosPin("u1", "Anna", 47.0, 8.0), SosPin.from(withPos))
        assertNull(SosPin.from(withPos.copy(location = null)))
        val json = Json.parseToJsonElement(MapGeoJson.sosPins(listOfNotNull(SosPin.from(withPos)))).jsonObject
        assertEquals("u1", json["features"]!!.jsonArray.single().jsonObject["properties"]!!.jsonObject[MapGeoJson.PROP_ID]!!.jsonPrimitive.content)
    }

    @Test
    fun `route is one line, points mark start and end`() {
        assertTrue(Json.parseToJsonElement(MapGeoJson.route(listOf(1.0 to 2.0))).jsonObject["features"]!!.jsonArray.isEmpty())
        val line = Json.parseToJsonElement(MapGeoJson.route(listOf(1.0 to 2.0, 1.1 to 2.1, 1.2 to 2.2))).jsonObject
        val coords = line["features"]!!.jsonArray.single().jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(3, coords.size)
        assertEquals("2.0", coords[0].jsonArray[0].jsonPrimitive.content) // lon first
        val points = Json.parseToJsonElement(MapGeoJson.routePoints(listOf(1.0 to 2.0, 1.1 to 2.1, 1.2 to 2.2))).jsonObject["features"]!!.jsonArray
        assertEquals(
            listOf("start", "point", "end"),
            points.map { it.jsonObject["properties"]!!.jsonObject[MapGeoJson.PROP_KIND]!!.jsonPrimitive.content },
        )
    }

    // ------------------------------------------------------------------ places dialog

    @Test
    fun `radius slider covers 50 to 2000 m with fine steps for small radii`() {
        assertEquals(PlaceInput.MIN_RADIUS_M, RadiusScale.toRadius(0f))
        assertEquals(PlaceInput.MAX_RADIUS_M, RadiusScale.toRadius(1f))
        assertEquals(PlaceInput.MAX_RADIUS_M, RadiusScale.toRadius(1.5f))
        assertTrue(RadiusScale.toRadius(0.25f) < 200) // a quarter of the slider stays small
        assertEquals(150, RadiusScale.toRadius(RadiusScale.toPosition(150)))
        assertEquals(0, RadiusScale.toRadius(0.37f) % 10)
        var previous = 0
        for (i in 0..100) {
            val r = RadiusScale.toRadius(i / 100f)
            assertTrue(r >= previous)
            previous = r
        }
    }

    // ------------------------------------------------------------------ history

    @Test
    fun `a day is local midnight to midnight, also on daylight saving days`() {
        val zone = ZoneId.of("Europe/Zurich")
        val (from, to) = HistoryModel.dayRange(LocalDate.of(2026, 10, 25), zone) // clocks go back: 25 h
        assertEquals("2026-10-24T22:00:00.000Z", Rfc3339.format(from))
        assertEquals("2026-10-25T23:00:00.000Z", Rfc3339.format(to))
    }

    @Test
    fun `track skips suspect points and is ordered oldest first`() {
        fun p(id: String, at: String, suspect: Boolean = false) = HistoryPoint(id, suspect, 1.0, 2.0, 5.0, recordedAt = at)
        val track = HistoryModel.track(
            listOf(
                p("c", "2026-10-18T10:00:00Z"),
                p("b", "2026-10-18T09:00:00Z", suspect = true),
                p("a", "2026-10-18T08:00:00Z"),
                p("x", "not a time"),
            ),
        )
        assertEquals(listOf("2026-10-18T08:00:00.000Z", "2026-10-18T10:00:00.000Z"), track.map { Rfc3339.format(it.atMs) })
    }
}
