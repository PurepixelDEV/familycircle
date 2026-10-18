package org.familycircle.android.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.familycircle.android.api.ApiClient
import org.familycircle.android.api.AuthSession
import org.familycircle.android.api.InMemoryTokenStore
import org.familycircle.android.api.Tokens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveHubTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var socket: LiveSocket
    private lateinit var hub: LiveHub

    private fun upgrade(vararg messages: String) = MockResponse.Builder().webSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                messages.forEach { webSocket.send(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        },
    ).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val http = OkHttpClient()
        val auth = AuthSession(InMemoryTokenStore(Tokens("access0", "refresh0", 0)))
        val api = ApiClient(http, auth, { base }, "test")
        socket = LiveSocket(http, auth, api::liveSocketRequest, ReconnectBackoff(baseMs = 10, maxMs = 20)) { 0.0 }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hub = LiveHub(socket, auth, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun `nobody holds the connection - no socket is opened`() = runBlocking {
        delay(300)
        assertEquals(0, server.requestCount)
        assertEquals(LiveConnectionState.IDLE, socket.state.value)
    }

    @Test
    fun `holders share one socket, passive listeners get its events, last release closes it`() = runBlocking {
        server.enqueue(upgrade("""{"type":"places_changed","family_id":"f1"}"""))

        val passive = scope.async { hub.signals.take(2).toList() }
        delay(50)
        hub.hold("location")
        hub.hold("map") // a second holder shares the socket
        val received = withTimeout(10_000) { passive.await() }

        assertEquals(LiveSignal.Connected, received[0])
        assertEquals(LiveSignal.Event(LiveEvent.PlacesChanged("f1")), received[1])
        assertEquals(setOf("location", "map"), hub.holderNames.value)
        assertEquals(1, server.requestCount)

        hub.release("location")
        hub.release("map")
        withTimeout(10_000) {
            while (hub.holderNames.value.isNotEmpty() || socket.state.value != LiveConnectionState.IDLE) delay(20)
        }
        assertTrue(hub.holderNames.value.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `connect holds the connection only while collected`() = runBlocking {
        server.enqueue(upgrade())
        val first = withTimeout(10_000) { hub.connect("map").first() }
        assertEquals(LiveSignal.Connected, first)
        withTimeout(10_000) {
            while (hub.holderNames.value.isNotEmpty() || socket.state.value != LiveConnectionState.IDLE) delay(20)
        }
    }
}
