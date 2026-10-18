package org.familycircle.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.familycircle.android.data.queue.PendingLocation
import org.familycircle.android.sync.toDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryTokenStore
    private lateinit var auth: AuthSession
    private lateinit var client: ApiClient
    private lateinit var base: String

    private val meJson = """{"id":"u1","email":"a@example.org","display_name":"Anna","locale":"de","is_instance_admin":false,"has_avatar":false,"avatar_version":null,"created_at":"2026-01-01T00:00:00Z"}"""
    private fun tokenJson(n: Int) =
        """{"access_token":"access$n","refresh_token":"refresh$n","token_type":"Bearer","expires_in":3600,"device_id":"d1","user":$meJson}"""
    private val settingsJson = """{"sharing_paused":false,"history_enabled":true,"history_retention_days":30,"share_battery":true}"""

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .body(body)
        .build()

    private fun error(code: Int, errorCode: String) = json(code, """{"error":{"code":"$errorCode","message":"m"}}""")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
        store = InMemoryTokenStore(Tokens("access0", "refresh0", 0))
        auth = AuthSession(store)
        client = ApiClient(OkHttpClient(), auth, { base }, "test")
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `instance is a GET with csrf header and parses`() = runBlocking {
        server.enqueue(json(200, """{"setup_required":false,"version":"0.1.0","map_style_url":"https://tiles/x","secure_context":true,"registration_mode":"invite_only"}"""))
        val info = client.instance(base)
        assertEquals("0.1.0", info.version)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/instance", req.url.encodedPath)
        assertEquals("1", req.headers["X-FC-CSRF"])
        assertNull(req.headers["Authorization"])
    }

    @Test
    fun `login posts the password grant with csrf header and stores tokens`() = runBlocking {
        store.clear()
        auth = AuthSession(store)
        client = ApiClient(OkHttpClient(), auth, { base }, "test")
        server.enqueue(json(200, tokenJson(7)))
        client.login(TokenRequest(grantType = "password", email = "a@example.org", password = "pw", deviceName = "Pixel"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/auth/token", req.url.encodedPath)
        assertEquals("1", req.headers["X-FC-CSRF"])
        assertNull(req.headers["Authorization"])
        assertTrue(req.headers["Content-Type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("password", body["grant_type"]!!.jsonPrimitive.content)
        assertEquals("Pixel", body["device_name"]!!.jsonPrimitive.content)
        assertEquals("access7", auth.accessToken())
        assertEquals("refresh7", store.load()!!.refreshToken)
    }

    @Test
    fun `wrong password maps to Unauthorized`() = runBlocking {
        server.enqueue(error(401, "invalid_credentials"))
        try {
            client.login(TokenRequest(grantType = "password", email = "a", password = "b", deviceName = "c"))
            fail()
        } catch (e: ApiException.Unauthorized) {
            assertEquals("invalid_credentials", e.code)
        }
    }

    @Test
    fun `server errors carry code and retry-after`() = runBlocking {
        server.enqueue(error(409, "email_taken"))
        try {
            client.login(TokenRequest(grantType = "password", email = "a", password = "b", deviceName = "c"))
            fail()
        } catch (e: ApiException.Server) {
            assertEquals(409, e.status)
            assertEquals("email_taken", e.code)
        }
        server.enqueue(MockResponse.Builder().code(429).setHeader("Retry-After", "12").body("""{"error":{"code":"rate_limited","message":"x"}}""").build())
        try {
            client.me()
            fail()
        } catch (e: ApiException.Server) {
            assertEquals(12L, e.retryAfterSec)
            assertTrue(e.isTransient)
        }
    }

    /** Dispatcher emulating the server's rotating refresh tokens. */
    private inner class RotatingServer(private val tokenDelayMs: Long = 0) : Dispatcher() {
        val refreshCalls = AtomicInteger()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            if (path == "/api/v1/auth/token") {
                val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                if (body["grant_type"]!!.jsonPrimitive.content != "refresh_token") return error(400, "invalid_input")
                if (request.headers["X-FC-CSRF"] != "1") return error(403, "csrf")
                val presented = body["refresh_token"]!!.jsonPrimitive.content
                val n = refreshCalls.incrementAndGet()
                if (presented != "refresh0") return error(401, "token_reused")
                Thread.sleep(tokenDelayMs)
                return json(200, tokenJson(n))
            }
            return when (request.headers["Authorization"]) {
                "Bearer access1" -> when (path) {
                    "/api/v1/me/settings" -> json(200, settingsJson)
                    "/api/v1/me" -> json(200, meJson)
                    else -> error(404, "not_found")
                }
                else -> error(401, "unauthorized")
            }
        }
    }

    @Test
    fun `401 triggers a refresh and one retry`() = runBlocking {
        val dispatcher = RotatingServer()
        server.dispatcher = dispatcher
        val settings = client.settings()
        assertTrue(settings.shareBattery)
        assertEquals(1, dispatcher.refreshCalls.get())
        assertEquals(3, server.requestCount)
        assertEquals("refresh1", store.load()!!.refreshToken)
    }

    @Test
    fun `concurrent 401s cause exactly one refresh request`() = runBlocking {
        val dispatcher = RotatingServer(tokenDelayMs = 200)
        server.dispatcher = dispatcher
        val results = (1..8).map { async(Dispatchers.IO) { client.me() } }.awaitAll()
        assertEquals(8, results.size)
        assertEquals(1, dispatcher.refreshCalls.get())
        assertTrue(auth.isLoggedIn)
    }

    @Test
    fun `token_reused on refresh ends the session`() = runBlocking {
        store.save(Tokens("accessX", "refreshX", 0))
        auth = AuthSession(store)
        client = ApiClient(OkHttpClient(), auth, { base }, "test")
        server.dispatcher = RotatingServer()
        try {
            client.me()
            fail("expected SessionExpired")
        } catch (e: ApiException.SessionExpired) {
            assertEquals("token_reused", e.code)
        }
        assertNull(store.load())
        assertEquals(AuthSession.State.LoggedOut, auth.state.value)
    }

    @Test
    fun `upload sends bearer and contract body`() = runBlocking {
        server.enqueue(json(200, """{"accepted":["3f2b8c1e-8d4a-4a3e-9f61-2c1d0e9b7a55"],"rejected":[]}"""))
        val p = PendingLocation("3f2b8c1e-8d4a-4a3e-9f61-2c1d0e9b7a55", 0, 1.0, 2.0, 5f, null, null, null, null, null, "unknown", 0)
        val result = client.uploadLocations(listOf(p.toDto()))
        assertEquals(1, result.accepted.size)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/locations", req.url.encodedPath)
        assertEquals("Bearer access0", req.headers["Authorization"])
        val points = Json.parseToJsonElement(req.body!!.utf8()).jsonObject["points"]!!.jsonArray
        assertEquals("1970-01-01T00:00:00.000Z", points[0].jsonObject["recorded_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun `share endpoints use PUT and DELETE with the user id path`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        client.grantShare("u2")
        client.revokeShare("u2")
        val put = server.takeRequest()
        val del = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/shares/u2", put.url.encodedPath)
        assertEquals("DELETE", del.method)
        assertEquals("/api/v1/shares/u2", del.url.encodedPath)
    }

    @Test
    fun `sub-path base url is respected`() = runBlocking {
        val subBase = server.url("/familycircle").toString().trimEnd('/')
        val c = ApiClient(OkHttpClient(), auth, { subBase }, "test")
        server.enqueue(json(200, meJson))
        c.me()
        assertEquals("/familycircle/api/v1/me", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `redirects are not followed`() = runBlocking {
        val c = ApiClient(ApiClient.defaultHttpClient(), auth, { base }, "test")
        server.enqueue(MockResponse.Builder().code(301).setHeader("Location", "https://elsewhere.example/").build())
        try {
            c.instance(base)
            fail()
        } catch (e: ApiException.Server) {
            assertEquals(301, e.status)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `garbage body maps to InvalidResponse`() = runBlocking {
        server.enqueue(json(200, "<html>not json</html>"))
        try {
            client.instance(base)
            fail()
        } catch (_: ApiException.InvalidResponse) {
        }
    }

    @Test
    fun `unreachable server maps to Network`() = runBlocking {
        server.close()
        try {
            client.instance(base)
            fail()
        } catch (_: ApiException.Network) {
        }
    }
}
