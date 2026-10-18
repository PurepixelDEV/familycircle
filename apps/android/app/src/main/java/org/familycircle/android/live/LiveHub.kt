package org.familycircle.android.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.familycircle.android.api.AuthSession
import org.familycircle.android.util.Logger

/**
 * One shared live connection for the whole app process.
 *
 * The WebSocket is open while at least one *holder* wants it and the user is signed in:
 * * `map` – the map screen while it is visible,
 * * `location` – the location service while sharing runs,
 * * `alerts` – the "always receive alarms" service.
 *
 * Everybody else (e.g. the alert processing) listens passively to [signals] and gets events only
 * while somebody holds the connection. So there is never more than one socket, and the map still
 * connects only while visible when nothing else needs the connection.
 */
class LiveHub(
    private val socket: LiveSocket,
    auth: AuthSession,
    scope: CoroutineScope,
) {
    private val holders = MutableStateFlow<Map<String, Int>>(emptyMap())

    private val _signals = MutableSharedFlow<LiveSignal>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Signals of the shared connection; collecting this does **not** keep the connection open. */
    val signals: SharedFlow<LiveSignal> = _signals.asSharedFlow()

    private val _holderNames = MutableStateFlow<Set<String>>(emptySet())

    /** Who currently keeps the connection open (diagnostics). */
    val holderNames: StateFlow<Set<String>> = _holderNames.asStateFlow()

    init {
        scope.launch {
            combine(holders, auth.state) { h, state -> h.isNotEmpty() && state == AuthSession.State.LoggedIn }
                .distinctUntilChanged()
                .collectLatest { wanted ->
                    if (wanted) socket.signals().collect { _signals.emit(it) }
                }
        }
    }

    fun hold(holder: String) {
        holders.update { it + (holder to (it[holder] ?: 0) + 1) }
        _holderNames.value = holders.value.keys
        Logger.d(TAG) { "hold $holder" }
    }

    fun release(holder: String) {
        holders.update { current ->
            val n = (current[holder] ?: 0) - 1
            if (n <= 0) current - holder else current + (holder to n)
        }
        _holderNames.value = holders.value.keys
        Logger.d(TAG) { "release $holder" }
    }

    /** Keeps the connection open while collected and emits its signals (subscribed before connecting). */
    fun connect(holder: String): Flow<LiveSignal> =
        _signals.onSubscription { hold(holder) }.onCompletion { release(holder) }

    private companion object {
        const val TAG = "LiveHub"
    }
}
