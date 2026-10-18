package org.familycircle.android.api

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.familycircle.android.live.LiveConnectionState
import org.familycircle.android.live.LiveEvent
import org.familycircle.android.live.LiveSignal
import org.familycircle.android.live.LiveSocket
import org.familycircle.android.live.ReconnectBackoff
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Server side of a test WebSocket that answers the client's close frame (like a real server). */
private open class ServerSocket : WebSocketListener() {
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }
}

class PairingAndLiveTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String

    private val meJson = """{"id":"u1","email":"a@example.org","display_name":"Anna","locale":"de","is_instance_admin":false,"has_avatar":true,"avatar_version":3,"created_at":"2026-01-01T00:00:00Z"}"""
    private fun tokenJson(n: Int) =
        """{"access_token":"access$n","refresh_token":"refresh$n","token_type":"Bearer","expires_in":3600,"device_id":"d$n","user":$meJson}"""

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .body(body)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ------------------------------------------------------------------ pairing grant

    @Test
    fun `pairing grant request JSON matches the contract`() {
        val body = ApiJson.encodeToString(TokenRequest.serializer(), TokenRequest.pairing("K7QF-2M9X-HD4R", "Pixel 9", "0.2.0"))
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("grant_type", "pairing_code", "device_name", "app_version"), obj.keys)
        assertEquals("pairing_code", obj["grant_type"]!!.jsonPrimitive.content)
        assertEquals("K7QF-2M9X-HD4R", obj["pairing_code"]!!.jsonPrimitive.content)
        assertEquals("Pixel 9", obj["device_name"]!!.jsonPrimitive.content)
        assertEquals("0.2.0", obj["app_version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pairing request never prints the code`() {
        assertFalse(TokenRequest.pairing("K7QF-2M9X-HD4R", "x", "y").toString().contains("K7QF"))
    }

    @Test
    fun `pair posts the grant to the given server and stores the tokens`() = runBlocking {
        val store = InMemoryTokenStore()
        val auth = AuthSession(store)
        // No server configured yet: pairing must use the explicit base URL.
        val client = ApiClient(OkHttpClient(), auth, { null }, "test")
        server.enqueue(json(200, tokenJson(1)))

        val response = client.pair(base, TokenRequest.pairing("AAAA-BBBB-CCCC", "Pixel", "0.2.0"))

        assertEquals("d1", response.deviceId)
        assertEquals(3L, response.user.avatarVersion)
        assertEquals("access1", store.load()!!.accessToken)
        assertEquals(AuthSession.State.LoggedIn, auth.state.value)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/auth/token", req.url.encodedPath)
        assertEquals("1", req.headers["X-FC-CSRF"])
        assertNull(req.headers["Authorization"])
        val sent = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("pairing_code", sent["grant_type"]!!.jsonPrimitive.content)
        assertEquals("AAAA-BBBB-CCCC", sent["pairing_code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid or used pairing code is Unauthorized and stores nothing`() = runBlocking {
        val store = InMemoryTokenStore()
        val auth = AuthSession(store)
        val client = ApiClient(OkHttpClient(), auth, { null }, "test")
        server.enqueue(json(401, """{"error":{"code":"invalid_grant","message":"m"}}"""))
        try {
            client.pair(base, TokenRequest.pairing("AAAA-BBBB-CCCC", "Pixel", "0.2.0"))
            fail("expected Unauthorized")
        } catch (e: ApiException.Unauthorized) {
            assertEquals("invalid_grant", e.code)
        }
        assertNull(store.load())
        assertEquals(AuthSession.State.LoggedOut, auth.state.value)
    }

    @Test
    fun `old server without pairing support answers 400`() = runBlocking {
        val client = ApiClient(OkHttpClient(), AuthSession(InMemoryTokenStore()), { null }, "test")
        server.enqueue(json(400, """{"error":{"code":"invalid_input","message":"m","fields":{"grant_type":"unsupported"}}}"""))
        try {
            client.pair(base, TokenRequest.pairing("AAAA-BBBB-CCCC", "Pixel", "0.2.0"))
            fail("expected Server")
        } catch (e: ApiException.Server) {
            assertEquals(400, e.status)
        }
    }

    // ------------------------------------------------------------------ avatars

    @Test
    fun `avatar is fetched with bearer token and version, 404 means none`() = runBlocking {
        val auth = AuthSession(InMemoryTokenStore(Tokens("access0", "refresh0", 0)))
        val client = ApiClient(OkHttpClient(), auth, { base }, "test")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x7F)
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "image/jpeg").body(okio.Buffer().write(jpeg)).build())
        server.enqueue(json(404, """{"error":{"code":"not_found","message":"m"}}"""))

        assertArrayEquals(jpeg, client.avatar("u-1", 7))
        assertNull(client.avatar("u-2", null))

        val first = server.takeRequest()
        assertEquals("/api/v1/users/u-1/avatar", first.url.encodedPath)
        assertEquals("7", first.url.queryParameter("v"))
        assertEquals("Bearer access0", first.headers["Authorization"])
        assertNull(server.takeRequest().url.queryParameter("v"))
    }

    // ------------------------------------------------------------------ live socket

    @Test
    fun `websocket request goes to api v1 ws with bearer`() {
        val client = ApiClient(OkHttpClient(), AuthSession(InMemoryTokenStore()), { "https://fc.example.org" }, "1.0")
        val req = client.liveSocketRequest("tok")!!
        assertEquals("https://fc.example.org/api/v1/ws", req.url.toString())
        assertEquals("Bearer tok", req.header("Authorization"))
        assertNull(ApiClient(OkHttpClient(), AuthSession(InMemoryTokenStore()), { null }, "1.0").liveSocketRequest("tok"))
    }

    @Test
    fun `live socket refreshes the token after a 401 handshake, reconnects and delivers events`() = runBlocking {
        val store = InMemoryTokenStore(Tokens("access0", "refresh0", 0))
        val auth = AuthSession(store)
        val http = OkHttpClient()
        val client = ApiClient(http, auth, { base }, "test")
        val socket = LiveSocket(http, auth, client::liveSocketRequest, ReconnectBackoff(baseMs = 10, maxMs = 20)) { 0.0 }

        server.enqueue(json(401, """{"error":{"code":"unauthorized","message":"m"}}""")) // handshake with access0
        server.enqueue(json(200, tokenJson(1))) // refresh
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : ServerSocket() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"hello","user_id":"u1","server_time":"2026-10-18T10:00:00Z"}""")
                        webSocket.send("""{"type":"location_hidden","user_id":"u2"}""")
                    }
                },
            ).build(),
        )

        val signals = withTimeout(10_000) { socket.signals().take(3).toList() }

        assertEquals(LiveSignal.Connected, signals[0])
        assertEquals(LiveSignal.Event(LiveEvent.Hello("u1")), signals[1])
        assertEquals(LiveSignal.Event(LiveEvent.LocationHidden("u2")), signals[2])
        assertEquals("access1", store.load()!!.accessToken)

        val handshake1 = server.takeRequest()
        assertEquals("/api/v1/ws", handshake1.url.encodedPath)
        assertEquals("Bearer access0", handshake1.headers["Authorization"])
        assertEquals("/api/v1/auth/token", server.takeRequest().url.encodedPath)
        val handshake2 = server.takeRequest()
        assertEquals("Bearer access1", handshake2.headers["Authorization"])
        assertEquals(LiveConnectionState.IDLE, socket.state.value) // collector cancelled → socket closed
    }

    @Test
    fun `live socket stops when the refresh is rejected`() = runBlocking {
        val store = InMemoryTokenStore(Tokens("access0", "refresh0", 0))
        val auth = AuthSession(store)
        val http = OkHttpClient()
        val client = ApiClient(http, auth, { base }, "test")
        val socket = LiveSocket(http, auth, client::liveSocketRequest, ReconnectBackoff(baseMs = 10, maxMs = 20)) { 0.0 }
        server.enqueue(json(401, """{"error":{"code":"unauthorized","message":"m"}}"""))
        server.enqueue(json(401, """{"error":{"code":"token_reused","message":"m"}}"""))

        val signals = withTimeout(10_000) { socket.signals().toList() }

        assertTrue(signals.isEmpty())
        assertEquals(AuthSession.State.LoggedOut, auth.state.value)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `live socket reconnects with backoff after the server drops the connection`() = runBlocking {
        val auth = AuthSession(InMemoryTokenStore(Tokens("access0", "refresh0", 0)))
        val http = OkHttpClient()
        val client = ApiClient(http, auth, { base }, "test")
        val socket = LiveSocket(http, auth, client::liveSocketRequest, ReconnectBackoff(baseMs = 10, maxMs = 20)) { 0.0 }
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : ServerSocket() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1001, "restart")
                    }
                },
            ).build(),
        )
        server.enqueue(json(503, "")) // reconnect attempt fails
        server.enqueue(MockResponse.Builder().webSocketUpgrade(ServerSocket()).build())

        val signals = withTimeout(10_000) { socket.signals().take(2).toList() }

        assertEquals(listOf(LiveSignal.Connected, LiveSignal.Connected), signals)
        assertEquals(3, server.requestCount)
    }
}
