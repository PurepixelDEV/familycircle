package org.familycircle.android.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.familycircle.android.data.queue.PendingLocation
import org.familycircle.android.sync.toDto
import org.familycircle.android.util.Rfc3339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    private fun valid(input: String) = ServerUrl.normalize(input) as ServerUrl.Result.Valid
    private fun reason(input: String) = (ServerUrl.normalize(input) as ServerUrl.Result.Invalid).reason

    @Test
    fun `trims and strips trailing slash`() {
        assertEquals("https://fc.example.org", valid("  https://fc.example.org/  ").url)
        assertEquals("https://fc.example.org", valid("https://fc.example.org").url)
    }

    @Test
    fun `assumes https without scheme`() {
        val v = valid("fc.example.org")
        assertEquals("https://fc.example.org", v.url)
        assertFalse(v.cleartext)
    }

    @Test
    fun `http is accepted but flagged as cleartext`() {
        val v = valid("http://192.168.1.10:8080/")
        assertEquals("http://192.168.1.10:8080", v.url)
        assertTrue(v.cleartext)
    }

    @Test
    fun `keeps sub paths and removes a pasted api suffix`() {
        assertEquals("https://example.org/familycircle", valid("https://example.org/familycircle/").url)
        assertEquals("https://example.org", valid("https://example.org/api/v1/").url)
        assertEquals("https://example.org/fc", valid("https://example.org/fc/api/v1").url)
    }

    @Test
    fun `scheme and host are lower-cased, default port dropped`() {
        assertEquals("https://fc.example.org", valid("HTTPS://FC.Example.org:443").url)
    }

    @Test
    fun `ipv6 literal`() {
        assertEquals("http://[fd00::1]:8080", valid("http://[fd00::1]:8080").url)
    }

    @Test
    fun `rejects other schemes and garbage`() {
        assertEquals(ServerUrl.Reason.EMPTY, reason("   "))
        assertEquals(ServerUrl.Reason.UNSUPPORTED_SCHEME, reason("ftp://example.org"))
        assertEquals(ServerUrl.Reason.UNSUPPORTED_SCHEME, reason("ws://example.org"))
        assertEquals(ServerUrl.Reason.MALFORMED, reason("https://"))
        assertEquals(ServerUrl.Reason.MALFORMED, reason("https://exa mple.org"))
    }

    @Test
    fun `rejects credentials, query and fragment`() {
        assertEquals(ServerUrl.Reason.CREDENTIALS, reason("https://user:pw@example.org"))
        assertEquals(ServerUrl.Reason.QUERY_OR_FRAGMENT, reason("https://example.org/?a=1"))
        assertEquals(ServerUrl.Reason.QUERY_OR_FRAGMENT, reason("https://example.org/#x"))
    }
}

class ContractJsonTest {
    private val point = PendingLocation(
        id = "3f2b8c1e-8d4a-4a3e-9f61-2c1d0e9b7a55",
        recordedAtMs = 1_792_315_202_123L,
        lat = 47.3769,
        lon = 8.5417,
        accuracyM = 12.3f,
        altitudeM = 408.5,
        speedMps = 1.4f,
        bearingDeg = 90.0f,
        batteryPct = 54,
        batteryCharging = false,
        motion = "walking",
        createdAtMs = 0,
    )

    @Test
    fun `point serialises with contract field names`() {
        val json = ApiJson.encodeToString(LocationUpload.serializer(), LocationUpload(listOf(point.toDto())))
        val obj = Json.parseToJsonElement(json).jsonObject["points"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0] as JsonObject }
        assertEquals(
            setOf("id", "recorded_at", "lat", "lon", "accuracy_m", "altitude_m", "speed_mps", "bearing_deg", "battery_pct", "battery_charging", "motion"),
            obj.keys,
        )
        assertEquals("2026-10-18T09:20:02.123Z", obj["recorded_at"]!!.jsonPrimitive.content)
        assertEquals("12.3", obj["accuracy_m"]!!.jsonPrimitive.content)
        assertEquals("walking", obj["motion"]!!.jsonPrimitive.content)
        assertEquals("54", obj["battery_pct"]!!.jsonPrimitive.content)
    }

    @Test
    fun `optional fields are omitted when null`() {
        val minimal = point.copy(altitudeM = null, speedMps = null, bearingDeg = null, batteryPct = null, batteryCharging = null)
        val obj = Json.parseToJsonElement(ApiJson.encodeToString(LocationPointDto.serializer(), minimal.toDto())).jsonObject
        assertEquals(setOf("id", "recorded_at", "lat", "lon", "accuracy_m", "motion"), obj.keys)
    }

    @Test
    fun `rfc3339 always has millis and Z`() {
        assertEquals("1970-01-01T00:00:00.000Z", Rfc3339.format(0))
        assertEquals("2026-10-18T09:20:02.120Z", Rfc3339.format(1_792_315_202_120L))
        assertEquals(1_792_315_202_123L, Rfc3339.parseMillis("2026-10-18T09:20:02.123Z"))
        assertEquals(1_792_315_202_123L, Rfc3339.parseMillis("2026-10-18T11:20:02.123456789+02:00"))
        assertEquals(null, Rfc3339.parseMillis("yesterday"))
    }

    @Test
    fun `token request uses contract names and omits nulls`() {
        val json = ApiJson.encodeToString(
            TokenRequest.serializer(),
            TokenRequest(grantType = "password", email = "a@b.ch", password = "x", deviceName = "Pixel", appVersion = "0.1.0"),
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("grant_type", "email", "password", "device_name", "app_version"), obj.keys)
    }

    @Test
    fun `family map with null location and unknown fields decodes`() {
        val body = """
            {"family_id":"f1","server_time":"2026-10-18T09:00:00Z","future_field":1,
             "members":[{"user_id":"u1","display_name":"A","color":"#1a2b3c","has_avatar":false,"avatar_version":null,"is_self":true,"location":null},
                        {"user_id":"u2","display_name":"B","color":"#aabbcc","has_avatar":true,"is_self":false,
                         "location":{"lat":1.5,"lon":2.5,"accuracy_m":20,"motion":"driving","recorded_at":"2026-10-18T08:59:00.5Z",
                                     "received_at":"2026-10-18T08:59:01Z","freshness":"live","battery_pct":null}}]}
        """.trimIndent()
        val map = ApiJson.decodeFromString(FamilyMap.serializer(), body)
        assertEquals(null, map.members[0].location)
        assertEquals("live", map.members[1].location!!.freshness)
        assertEquals(20.0, map.members[1].location!!.accuracyM, 0.0)
    }
}
