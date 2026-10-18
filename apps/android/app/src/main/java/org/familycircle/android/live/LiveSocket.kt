package org.familycircle.android.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.AuthSession
import org.familycircle.android.util.Logger
import java.util.concurrent.TimeUnit

/** Connection state of the live WebSocket, shown in the diagnostics. */
enum class LiveConnectionState { IDLE, CONNECTING, CONNECTED, RECONNECTING }

/** What a [LiveSocket.signals] collector receives. */
sealed interface LiveSignal {
    /** (Re)connected: the collector must refetch the map, events may have been missed. */
    data object Connected : LiveSignal

    data class Event(val event: LiveEvent) : LiveSignal
}

/**
 * Live updates over `GET /api/v1/ws` with `Authorization: Bearer`.
 *
 * [signals] is a cold flow: the socket only exists while it is collected – only [LiveHub] does that,
 * so the whole app shares one connection. Reconnects use [ReconnectBackoff]; after a connection
 * that was open the attempt counter starts over. A 401 during the handshake refreshes the token once
 * through the serialised [AuthSession] and reconnects; a rejected refresh ends the flow (the app
 * returns to sign-in).
 *
 * @param requestFor builds the handshake request for an access token (null: no server configured).
 */
class LiveSocket(
    http: OkHttpClient,
    private val auth: AuthSession,
    private val requestFor: (accessToken: String) -> Request?,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val random: () -> Double = Math::random,
) {
    private val client: OkHttpClient = http.newBuilder()
        .pingInterval(25, TimeUnit.SECONDS) // detects dead connections (e.g. after a network switch)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(LiveConnectionState.IDLE)
    val state: StateFlow<LiveConnectionState> = _state.asStateFlow()

    /** Wall-clock time of the last successful connection (diagnostics), null if never. */
    @Volatile
    var lastConnectedAtMs: Long? = null
        private set

    private sealed interface Msg {
        data object Open : Msg
        data class Text(val text: String) : Msg
        data class Failure(val httpCode: Int?) : Msg
        data object Closed : Msg
    }

    private enum class Outcome { DROPPED, FAILED, UNAUTHORIZED }

    fun signals(): Flow<LiveSignal> = flow {
        var attempt = 0
        var refreshedAfter401 = false
        try {
            while (currentCoroutineContext().isActive) {
                val token = auth.accessToken() ?: return@flow
                val request = requestFor(token) ?: return@flow
                _state.value = if (attempt == 0) LiveConnectionState.CONNECTING else LiveConnectionState.RECONNECTING
                var opened = false
                val outcome = session(request) { signal ->
                    if (signal is LiveSignal.Connected) {
                        opened = true
                        refreshedAfter401 = false
                        _state.value = LiveConnectionState.CONNECTED
                        lastConnectedAtMs = System.currentTimeMillis()
                    }
                    emit(signal)
                }
                if (outcome == Outcome.UNAUTHORIZED && !refreshedAfter401) {
                    refreshedAfter401 = true
                    try {
                        auth.refreshAfterUnauthorized(token)
                        Logger.i(TAG, "token refreshed after 401, reconnecting")
                        continue
                    } catch (e: ApiException.SessionExpired) {
                        Logger.i(TAG, "session ended, live updates stopped")
                        return@flow
                    } catch (e: ApiException) {
                        Logger.w(TAG, "token refresh failed, retrying later", e)
                    }
                }
                if (opened) attempt = 0
                _state.value = LiveConnectionState.RECONNECTING
                val wait = backoff.delayMs(attempt, random())
                Logger.d(TAG) { "reconnect in ${wait}ms (attempt $attempt, $outcome)" }
                delay(wait)
                attempt++
            }
        } finally {
            _state.value = LiveConnectionState.IDLE
        }
    }

    /** One WebSocket connection; returns when it ends. Closes the socket when cancelled. */
    private suspend fun session(request: Request, onSignal: suspend (LiveSignal) -> Unit): Outcome {
        val channel = Channel<Msg>(Channel.UNLIMITED)
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    channel.trySend(Msg.Open)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    channel.trySend(Msg.Text(text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    channel.trySend(Msg.Closed)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    channel.trySend(Msg.Closed)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    channel.trySend(Msg.Failure(response?.code))
                }
            },
        )
        var opened = false
        try {
            for (msg in channel) {
                when (msg) {
                    Msg.Open -> {
                        opened = true
                        Logger.i(TAG, "connected")
                        onSignal(LiveSignal.Connected)
                    }
                    is Msg.Text -> LiveEvent.parse(msg.text)?.let { onSignal(LiveSignal.Event(it)) }
                    Msg.Closed -> return if (opened) Outcome.DROPPED else Outcome.FAILED
                    is Msg.Failure -> {
                        if (msg.httpCode == 401) return Outcome.UNAUTHORIZED
                        Logger.i(TAG, "connection failed (http ${msg.httpCode ?: "-"})")
                        return if (opened) Outcome.DROPPED else Outcome.FAILED
                    }
                }
            }
            return Outcome.FAILED
        } finally {
            if (opened) socket.close(NORMAL_CLOSURE, null) else socket.cancel()
            channel.close()
        }
    }

    private companion object {
        const val TAG = "Live"
        const val NORMAL_CLOSURE = 1000
    }
}
