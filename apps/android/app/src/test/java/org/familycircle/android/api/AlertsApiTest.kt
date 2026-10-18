package org.familycircle.android.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.familycircle.android.util.Rfc3339
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** API 0.3 endpoints (docs/ALERTS.md) against packages/api-contract/openapi.yaml. */
class AlertsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .body(body)
        .build()

    private fun noContent() = MockResponse.Builder().code(204).build()

    private fun error(code: Int, errorCode: String) = json(code, """{"error":{"code":"$errorCode","message":"m"}}""")

    private fun RecordedRequest.json() = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private val sosAlert = """{"id":42,"kind":"sos","actor":{"user_id":"u2","display_name":"Anna"},"from_device_id":"d9",
        "place":null,"location":{"lat":47.37,"lon":8.54,"accuracy_m":null},"battery_pct":null,"message":null,
        "created_at":"2026-10-18T10:00:00Z","expires_at":null,"resolved_at":null,"active":true,"mine":false}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        client = ApiClient(OkHttpClient(), AuthSession(InMemoryTokenStore(Tokens("access0", "refresh0", 0))), { base }, "test")
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `alerts catch-up sends the cursor and parses every field`() = runBlocking {
        server.enqueue(json(200, """{"alerts":[$sosAlert,{"id":43,"kind":"arrival","actor":{"user_id":"u3","display_name":"Bob"},
            "from_device_id":null,"place":{"id":"p1","name":"Schule"},"location":null,"battery_pct":null,"message":null,
            "created_at":"2026-10-18T10:01:00Z","expires_at":null,"resolved_at":null,"active":false,"mine":false,"future":1}],
            "server_time":"2026-10-18T10:02:00Z"}"""))

        val response = client.alerts(after = 41, limit = 100)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/alerts", req.url.encodedPath)
        assertEquals("41", req.url.queryParameter("after"))
        assertEquals("100", req.url.queryParameter("limit"))
        assertEquals("Bearer access0", req.headers["Authorization"])
        assertEquals(2, response.alerts.size)
        val sos = response.alerts[0]
        assertEquals(42L, sos.id)
        assertEquals(AlertKind.SOS, sos.kind)
        assertEquals("Anna", sos.actor.displayName)
        assertEquals("d9", sos.fromDeviceId)
        assertEquals(47.37, sos.location!!.lat, 1e-9)
        assertNull(sos.location!!.accuracyM)
        assertTrue(sos.active)
        assertFalse(sos.mine)
        assertEquals("Schule", response.alerts[1].place!!.name)
        assertNull(response.alerts[1].fromDeviceId)
        // Positions never appear in toString (logs).
        assertFalse(sos.toString().contains("47.37"))
    }

    @Test
    fun `sos with position posts it, without position posts an empty body`() = runBlocking {
        server.enqueue(json(201, """{"alert":$sosAlert,"recipients":3}"""))
        server.enqueue(json(201, """{"alert":$sosAlert,"recipients":3}"""))

        val created = client.sendSos(PositionDto(47.1, 8.2, null))
        client.sendSos(null)

        assertEquals(3, created.recipients)
        val withPos = server.takeRequest()
        assertEquals("POST", withPos.method)
        assertEquals("/api/v1/sos", withPos.url.encodedPath)
        val body = withPos.json()
        assertEquals(setOf("lat", "lon"), body.keys) // accuracy omitted when unknown
        assertEquals("47.1", body["lat"]!!.jsonPrimitive.content)
        val empty = server.takeRequest()
        assertEquals(0L, empty.bodySize)
    }

    @Test
    fun `resolve sos returns the count`() = runBlocking {
        server.enqueue(json(200, """{"resolved":1}"""))
        assertEquals(1, client.resolveSos())
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/sos/resolve", req.url.encodedPath)
    }

    @Test
    fun `check-in sends position and message`() = runBlocking {
        server.enqueue(json(201, """{"alert":${sosAlert.replace("\"sos\"", "\"check_in\"")},"recipients":2}"""))
        client.checkIn(CheckInRequest(47.0, 8.0, 12.5, "Gut angekommen"))
        val req = server.takeRequest()
        assertEquals("/api/v1/check-ins", req.url.encodedPath)
        val body = req.json()
        assertEquals("Gut angekommen", body["message"]!!.jsonPrimitive.content)
        assertEquals("12.5", body["accuracy_m"]!!.jsonPrimitive.content)
    }

    @Test
    fun `battery alert sends reason and level and parses both answers`() = runBlocking {
        server.enqueue(json(201, """{"sent":true,"recipients":2}"""))
        server.enqueue(json(200, """{"sent":false}"""))

        val sent = client.batteryAlert(BatteryAlertRequest(BatteryAlertRequest.LOW, 14, 47.0, 8.0, 20.0))
        val notSent = client.batteryAlert(BatteryAlertRequest(BatteryAlertRequest.SHUTDOWN, 3))

        assertTrue(sent.sent)
        assertEquals(2, sent.recipients)
        assertFalse(notSent.sent)
        val low = server.takeRequest()
        assertEquals("/api/v1/battery-alerts", low.url.encodedPath)
        assertEquals("low", low.json()["reason"]!!.jsonPrimitive.content)
        assertEquals("14", low.json()["battery_pct"]!!.jsonPrimitive.content)
        val shutdown = server.takeRequest().json()
        assertEquals(setOf("reason", "battery_pct"), shutdown.keys) // no position: the server uses the latest
    }

    @Test
    fun `ring and locate return the request, 403 means not allowed`() = runBlocking {
        server.enqueue(json(202, """{"alert_id":7,"expires_at":"2026-10-18T10:02:00Z","online":true}"""))
        server.enqueue(json(202, """{"alert_id":8,"expires_at":"2026-10-18T10:05:00Z","online":false}"""))
        server.enqueue(error(403, "forbidden"))

        val ring = client.ring("u2")
        val locate = client.locate("u2")
        try {
            client.ring("u3")
            fail("expected 403")
        } catch (e: ApiException.Server) {
            assertEquals(403, e.status)
        }

        assertTrue(ring.online)
        assertEquals(8L, locate.alertId)
        assertFalse(locate.online)
        assertEquals("/api/v1/users/u2/ring", server.takeRequest().url.encodedPath)
        val loc = server.takeRequest()
        assertEquals("POST", loc.method)
        assertEquals("/api/v1/users/u2/locate", loc.url.encodedPath)
    }

    @Test
    fun `history asks for one day and parses suspect points`() = runBlocking {
        server.enqueue(json(200, """{"points":[{"id":"h1","suspect":true,"lat":1.0,"lon":2.0,"accuracy_m":5,"motion":"walking",
            "recorded_at":"2026-10-18T09:00:00Z","received_at":"2026-10-18T09:00:01Z","freshness":"stale","battery_pct":null},
            {"id":"h2","suspect":false,"lat":1.1,"lon":2.1,"accuracy_m":7.5,"motion":"unknown","recorded_at":"2026-10-18T08:00:00Z",
            "received_at":"2026-10-18T08:00:01Z","freshness":"stale"}]}"""))
        val from = Rfc3339.parseMillis("2026-10-17T22:00:00Z")!!
        val to = Rfc3339.parseMillis("2026-10-18T22:00:00Z")!!

        val points = client.history("u2", from, to)

        val req = server.takeRequest()
        assertEquals("/api/v1/users/u2/locations", req.url.encodedPath)
        assertEquals("2026-10-17T22:00:00.000Z", req.url.queryParameter("from"))
        assertEquals("2026-10-18T22:00:00.000Z", req.url.queryParameter("to"))
        assertEquals("5000", req.url.queryParameter("limit"))
        assertEquals(2, points.size)
        assertTrue(points[0].suspect)
        assertEquals(7.5, points[1].accuracyM, 1e-9)
    }

    @Test
    fun `history grant is a PATCH with history only`() = runBlocking {
        server.enqueue(noContent())
        client.setShareHistory("u2", true)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/v1/shares/u2", req.url.encodedPath)
        assertEquals("""{"history":true}""", req.body!!.utf8())
    }

    @Test
    fun `shares carry the history flag, older servers default to false`() = runBlocking {
        server.enqueue(json(200, """{"outgoing":[{"user_id":"u2","display_name":"Anna","since":"2026-01-01T00:00:00Z","history":true}],
            "incoming":[{"user_id":"u3","display_name":"Bob","since":"2026-01-01T00:00:00Z"}]}"""))
        val shares = client.shares()
        assertTrue(shares.outgoing.single().history)
        assertFalse(shares.incoming.single().history)
    }

    @Test
    fun `settings know the new opt-ins and default them for older servers`() = runBlocking {
        server.enqueue(json(200, """{"sharing_paused":false,"history_enabled":true,"history_retention_days":30,"share_battery":true,
            "allow_ring":true,"allow_locate_request":true,"battery_alerts":false}"""))
        server.enqueue(json(200, """{"sharing_paused":false,"history_enabled":true,"history_retention_days":30,"share_battery":true}"""))
        server.enqueue(json(200, """{"sharing_paused":false,"history_enabled":true,"history_retention_days":30,"share_battery":true,
            "allow_ring":true,"allow_locate_request":false,"battery_alerts":true}"""))

        val full = client.settings()
        val old = client.settings()
        client.updateSettings(PrivacySettingsUpdate(allowRing = true))

        assertTrue(full.allowRing)
        assertTrue(full.allowLocateRequest)
        assertFalse(full.batteryAlerts)
        assertFalse(old.allowRing)
        assertFalse(old.allowLocateRequest)
        assertTrue(old.batteryAlerts)
        server.takeRequest()
        server.takeRequest()
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("""{"allow_ring":true}""", patch.body!!.utf8()) // only the changed field
    }

    @Test
    fun `places list, create, update, delete and mute`() = runBlocking {
        server.enqueue(json(200, """{"places":[{"id":"p1","family_id":"f1","name":"Zuhause","lat":47.0,"lon":8.0,"radius_m":150,
            "created_by":"u1","created_at":"2026-10-18T10:00:00Z","muted":true,"present":["u1","u2"]}]}"""))
        server.enqueue(json(201, """{"id":"p2"}"""))
        server.enqueue(noContent())
        server.enqueue(noContent())
        server.enqueue(noContent())
        server.enqueue(noContent())

        val places = client.places("f1")
        val id = client.createPlace("f1", PlaceInput("Schule", 47.1, 8.1, 200))
        client.updatePlace("p1", PlaceInput(radiusM = 300))
        client.deletePlace("p1")
        client.setPlaceMuted("p2", muted = true)
        client.setPlaceMuted("p2", muted = false)

        assertTrue(places.single().muted)
        assertEquals(listOf("u1", "u2"), places.single().present)
        assertEquals("p2", id)
        assertEquals("/api/v1/families/f1/places", server.takeRequest().url.encodedPath)
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals(setOf("name", "lat", "lon", "radius_m"), create.json().keys)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/v1/places/p1", patch.url.encodedPath)
        assertEquals("""{"radius_m":300}""", patch.body!!.utf8())
        assertEquals("DELETE", server.takeRequest().method)
        val mute = server.takeRequest()
        assertEquals("PUT", mute.method)
        assertEquals("/api/v1/places/p2/mute", mute.url.encodedPath)
        val unmute = server.takeRequest()
        assertEquals("DELETE", unmute.method)
        assertEquals("/api/v1/places/p2/mute", unmute.url.encodedPath)
    }

    @Test
    fun `too many places is a 409`() = runBlocking {
        server.enqueue(error(409, "conflict"))
        try {
            client.createPlace("f1", PlaceInput("X", 1.0, 2.0, 100))
            fail("expected 409")
        } catch (e: ApiException.Server) {
            assertEquals(409, e.status)
        }
    }
}
