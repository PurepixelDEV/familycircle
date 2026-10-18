package org.familycircle.android.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.familycircle.android.api.MapMember
import org.familycircle.android.api.MemberLocation
import org.familycircle.android.ui.family.Freshness
import org.familycircle.android.util.Rfc3339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerModelTest {
    private val now = Rfc3339.parseMillis("2026-10-18T12:00:00Z")!!

    private fun at(msAgo: Long) = Rfc3339.format(now - msAgo)

    private fun loc(msAgo: Long, freshness: String = "live", acc: Double = 10.0, motion: String = "walking", battery: Int? = null) =
        MemberLocation(
            lat = 47.0, lon = 8.0, accuracyM = acc, motion = motion, recordedAt = at(msAgo), receivedAt = at(msAgo),
            freshness = freshness, batteryPct = battery,
        )

    private fun member(id: String, location: MemberLocation?, self: Boolean = false, color: String = "#aabbcc") =
        MapMember(userId = id, displayName = "Name $id", color = color, hasAvatar = false, isSelf = self, location = location)

    private fun single(location: MemberLocation?) = MarkerModel.build(listOf(member("a", location)), null, now)

    @Test
    fun `freshness boundaries are computed from recorded_at`() {
        assertEquals(Freshness.LIVE, single(loc(0)).single().freshness)
        assertEquals(Freshness.LIVE, single(loc(119_999)).single().freshness)
        assertEquals(Freshness.RECENT, single(loc(120_000)).single().freshness)
        assertEquals(Freshness.RECENT, single(loc(899_999)).single().freshness)
        assertEquals(Freshness.STALE, single(loc(900_000)).single().freshness)
    }

    @Test
    fun `stale is never shown as live even if the server says live`() {
        val m = single(loc(3_600_000, freshness = "live")).single()
        assertEquals(Freshness.STALE, m.freshness)
        assertFalse(m.showMotion)
        assertEquals(MapGeoJson.STALE_COLOR, MapGeoJson.colorOf(m))
    }

    @Test
    fun `the stricter server value wins`() {
        assertEquals(Freshness.STALE, single(loc(10_000, freshness = "stale")).single().freshness)
        assertEquals(Freshness.RECENT, single(loc(10_000, freshness = "recent")).single().freshness)
    }

    @Test
    fun `unparsable recorded_at is stale without age`() {
        val m = single(loc(0).copy(recordedAt = "garbage")).single()
        assertEquals(Freshness.STALE, m.freshness)
        assertNull(m.recordedAtMs)
    }

    @Test
    fun `future timestamps (clock skew) count as live, not negative age`() {
        assertEquals(Freshness.LIVE, single(loc(-30_000)).single().freshness)
    }

    @Test
    fun `members without location have no marker`() {
        assertTrue(single(null).isEmpty())
    }

    @Test
    fun `invalid coordinates are dropped`() {
        assertTrue(single(loc(0).copy(lat = Double.NaN)).isEmpty())
        assertTrue(single(loc(0).copy(lat = 91.0)).isEmpty())
    }

    @Test
    fun `accuracy circle only from 15 m`() {
        assertFalse(single(loc(0, acc = 14.9)).single().showAccuracyCircle)
        assertTrue(single(loc(0, acc = 15.0)).single().showAccuracyCircle)
    }

    @Test
    fun `battery comes only from the server data`() {
        assertNull(single(loc(0)).single().batteryPct)
        assertEquals(55, single(loc(0, battery = 55)).single().batteryPct)
    }

    @Test
    fun `own newer local fix replaces the server position of the self marker`() {
        val local = LocalFix(recordedAtMs = now - 5_000, lat = 46.0, lon = 7.0, accuracyM = 8.0, motion = "stationary")
        val markers = MarkerModel.build(listOf(member("me", loc(600_000, freshness = "recent"), self = true)), local, now)
        val m = markers.single()
        assertEquals(MarkerSource.DEVICE, m.source)
        assertEquals(46.0, m.lat, 0.0)
        assertEquals(Freshness.LIVE, m.freshness)
        assertTrue(m.isSelf)
    }

    @Test
    fun `older local fix does not replace a newer server position`() {
        val local = LocalFix(recordedAtMs = now - 600_000, lat = 46.0, lon = 7.0, accuracyM = 8.0, motion = "stationary")
        val m = MarkerModel.build(listOf(member("me", loc(5_000), self = true)), local, now).single()
        assertEquals(MarkerSource.SERVER, m.source)
        assertEquals(47.0, m.lat, 0.0)
    }

    @Test
    fun `local fix is used when the server has no own position, and never for others`() {
        val local = LocalFix(recordedAtMs = now - 1_000_000, lat = 46.0, lon = 7.0, accuracyM = 8.0, motion = "walking")
        val markers = MarkerModel.build(listOf(member("me", null, self = true), member("other", null)), local, now)
        assertEquals(listOf("me"), markers.map { it.userId })
        assertEquals(Freshness.STALE, markers.single().freshness)
    }

    @Test
    fun `local fix without a self member is not shown`() {
        val local = LocalFix(recordedAtMs = now, lat = 46.0, lon = 7.0, accuracyM = 8.0, motion = "walking")
        assertTrue(MarkerModel.build(emptyList(), local, now).isEmpty())
    }

    @Test
    fun `invalid colours fall back`() {
        assertEquals(MarkerModel.FALLBACK_COLOR, MarkerModel.color("red"))
        assertEquals(MarkerModel.FALLBACK_COLOR, MarkerModel.color(null))
        assertEquals("#aabbcc", MarkerModel.color("#AABBCC"))
    }

    @Test
    fun initials() {
        assertEquals("AM", MarkerModel.initials("Anna Maria Muster".replace("Maria ", "")))
        assertEquals("B", MarkerModel.initials(" bob "))
        assertEquals("?", MarkerModel.initials("  "))
        assertEquals("AC", MarkerModel.initials("anna b clara"))
    }
}

class MapGeoJsonTest {
    private val spec = MarkerSpec(
        userId = "u\"1", name = "A", colorHex = "#112233", isSelf = false, hasAvatar = false, avatarVersion = null,
        lat = 47.5, lon = 8.25, accuracyM = 30.0, recordedAtMs = 0, freshness = Freshness.LIVE, motion = "walking",
        batteryPct = null, batteryCharging = null, source = MarkerSource.SERVER,
    )

    @Test
    fun `markers are valid GeoJSON with lon-lat order and escaped properties`() {
        val json = Json.parseToJsonElement(MapGeoJson.markers(listOf(spec), "u\"1") { "icon-1" }).jsonObject
        assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
        val f = json["features"]!!.jsonArray.single().jsonObject
        val coords = f["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(8.25, coords[0].jsonPrimitive.double, 0.0)
        assertEquals(47.5, coords[1].jsonPrimitive.double, 0.0)
        val p = f["properties"]!!.jsonObject
        assertEquals("u\"1", p["id"]!!.jsonPrimitive.content)
        assertEquals("icon-1", p["icon"]!!.jsonPrimitive.content)
        assertTrue(p["live"]!!.jsonPrimitive.boolean)
        assertTrue(p["selected"]!!.jsonPrimitive.boolean)
        assertEquals("#112233", p["color"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stale markers are slate and not live`() {
        val stale = spec.copy(freshness = Freshness.STALE)
        val p = Json.parseToJsonElement(MapGeoJson.markers(listOf(stale), null) { "i" }).jsonObject["features"]!!
            .jsonArray.single().jsonObject["properties"]!!.jsonObject
        assertFalse(p["live"]!!.jsonPrimitive.boolean)
        assertEquals(MapGeoJson.STALE_COLOR, p["color"]!!.jsonPrimitive.content)
    }

    @Test
    fun `selected sorts above self above fresher`() {
        val self = spec.copy(userId = "me", isSelf = true)
        val stale = spec.copy(userId = "s", freshness = Freshness.STALE)
        assertTrue(MapGeoJson.sortKey(spec, spec.userId) > MapGeoJson.sortKey(self, spec.userId))
        assertTrue(MapGeoJson.sortKey(self, null) > MapGeoJson.sortKey(spec, null))
        assertTrue(MapGeoJson.sortKey(spec, null) > MapGeoJson.sortKey(stale, null))
    }

    @Test
    fun `accuracy polygons only for large circles and closed rings`() {
        val small = spec.copy(userId = "small", accuracyM = 5.0)
        val json = Json.parseToJsonElement(MapGeoJson.accuracy(listOf(spec, small))).jsonObject
        val features = json["features"]!!.jsonArray
        assertEquals(1, features.size)
        val ring = features[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals(49, ring.size)
        assertEquals(ring.first(), ring.last())
    }

    @Test
    fun `circle polygon has the requested radius`() {
        val ring = MapGeoJson.circlePolygon(lat = 0.0, lon = 0.0, radiusM = 111_320.0, steps = 4)
        assertEquals(5, ring.size)
        assertEquals(1.0, ring[0].first, 1e-9) // east: +1° lon at the equator
        assertEquals(1.0, ring[1].second, 1e-9) // north: +1° lat
        assertEquals(ring.first(), ring.last())
    }
}

class CameraFitTest {
    private val pad = EdgeInsets(10, 20, 30, 40)

    @Test
    fun `no points no camera move`() {
        assertEquals(CameraPlan.None, CameraFit.fitAll(emptyList(), pad))
    }

    @Test
    fun `single point centers at zoom 15`() {
        assertEquals(CameraPlan.Center(47.0, 8.0, 15.0, pad), CameraFit.fitAll(listOf(47.0 to 8.0), pad))
    }

    @Test
    fun `nearly identical points are treated as one`() {
        val plan = CameraFit.fitAll(listOf(47.0 to 8.0, 47.00001 to 8.00001), pad)
        assertTrue(plan is CameraPlan.Center)
    }

    @Test
    fun `bounds cover all points`() {
        val plan = CameraFit.fitAll(listOf(47.0 to 8.0, 46.0 to 9.5, 47.5 to 7.0), pad)
        assertEquals(CameraPlan.Bounds(north = 47.5, east = 9.5, south = 46.0, west = 7.0, padding = pad), plan)
    }

    @Test
    fun `focus keeps a closer zoom`() {
        assertEquals(17.0, (CameraFit.focus(1.0, 2.0, 17.0, pad) as CameraPlan.Center).zoom, 0.0)
        assertEquals(15.0, (CameraFit.focus(1.0, 2.0, 5.0, pad) as CameraPlan.Center).zoom, 0.0)
    }

    @Test
    fun `padding respects the bottom sheet and the marker height`() {
        // 400x800 dp phone at density 1, sheet covers 300 px, chips 60 px.
        val p = CameraFit.padding(400, 800, coveredBottomPx = 300, coveredLeftPx = 0, topOverlayPx = 60, density = 1f)
        assertEquals(300 + 24, p.bottom)
        assertEquals(60 + 84 + 8, p.top)
        assertEquals(40, p.left)
        assertEquals(40, p.right)
    }

    @Test
    fun `padding scales with density`() {
        val p = CameraFit.padding(1200, 2400, 0, 0, 0, density = 3f)
        assertEquals(120, p.left)
        assertEquals((84 + 8) * 3, p.top)
    }

    @Test
    fun `padding never leaves less than 48 dp of map`() {
        // Landscape phone with an almost fully expanded sheet.
        val p = CameraFit.padding(640, 300, coveredBottomPx = 290, coveredLeftPx = 0, topOverlayPx = 60, density = 1f)
        assertTrue(p.top + p.bottom <= 300 - 48)
        assertTrue(p.bottom > p.top) // proportions kept
        val narrow = CameraFit.padding(60, 800, 0, coveredLeftPx = 100, topOverlayPx = 0, density = 1f)
        assertTrue(narrow.left + narrow.right <= 12)
        assertTrue(narrow.left >= 0 && narrow.right >= 0)
    }

    @Test
    fun `negative inputs are treated as zero`() {
        val p = CameraFit.padding(400, 800, coveredBottomPx = -50, coveredLeftPx = -10, topOverlayPx = -1, density = 1f)
        assertEquals(24, p.bottom)
        assertEquals(40, p.left)
        assertNotEquals(0, p.top)
    }
}

class MapStyleTest {
    @Test
    fun `absolute urls are kept, relative paths resolved, junk rejected`() {
        assertEquals("https://tiles.openfreemap.org/styles/liberty", MapStyle.resolve("https://tiles.openfreemap.org/styles/liberty", "http://10.0.2.2:9092"))
        assertEquals("http://10.0.2.2:9092/map/style.json", MapStyle.resolve("/map/style.json", "http://10.0.2.2:9092"))
        assertNull(MapStyle.resolve("", "https://fc.example.org"))
        assertNull(MapStyle.resolve(null, "https://fc.example.org"))
        assertNull(MapStyle.resolve("//evil.example/style.json", "https://fc.example.org"))
        assertNull(MapStyle.resolve("file:///sdcard/style.json", "https://fc.example.org"))
        assertNull(MapStyle.resolve("/style.json", null))
    }
}
