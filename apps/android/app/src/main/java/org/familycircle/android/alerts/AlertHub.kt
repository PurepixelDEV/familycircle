package org.familycircle.android.alerts

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import org.familycircle.android.api.Alert
import org.familycircle.android.api.AlertsResponse
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.ApiJson
import org.familycircle.android.live.LiveEvent
import org.familycircle.android.live.LiveSignal
import org.familycircle.android.util.Logger

/** Persistence of [AlertState]. */
interface AlertStateStore {
    fun load(): AlertState

    fun save(state: AlertState)

    fun clear()
}

class InMemoryAlertStateStore(private var state: AlertState = AlertState()) : AlertStateStore {
    override fun load() = state
    override fun save(state: AlertState) {
        this.state = state
    }
    override fun clear() {
        state = AlertState()
    }
}

/** Own preferences file; nothing of it is part of a backup (allowBackup=false). */
class PrefsAlertStateStore(context: Context) : AlertStateStore {
    private val sp: SharedPreferences = context.getSharedPreferences("fc_alerts", Context.MODE_PRIVATE)

    override fun load(): AlertState = sp.getString(KEY, null)?.let { raw ->
        try {
            ApiJson.decodeFromString(AlertState.serializer(), raw)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    } ?: AlertState()

    override fun save(state: AlertState) = sp.edit { putString(KEY, ApiJson.encodeToString(AlertState.serializer(), state)) }

    override fun clear() = sp.edit { clear() }

    private companion object {
        const val KEY = "state"
    }
}

/** Executes [AlertEffect]s (notifications, ringing, fresh position). */
fun interface AlertEffectHandler {
    fun handle(effect: AlertEffect)
}

/** SOS overview for the UI. */
data class SosBoard(val others: List<Alert> = emptyList(), val own: Alert? = null)

/**
 * Receives alerts from the shared live connection and catches up with `GET /alerts?after=` after
 * every (re)connect and from the periodic worker. Everything goes through [AlertProcessor] under
 * one mutex, so live delivery and catch-up never handle an alert twice.
 *
 * @param fetch `GET /alerts?after=&limit=`.
 * @param ownDeviceId device id of this phone (stored at sign-in).
 */
class AlertHub(
    private val fetch: suspend (after: Long, limit: Int) -> AlertsResponse,
    private val store: AlertStateStore,
    private val handler: AlertEffectHandler,
    private val ownDeviceId: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var state: AlertState = store.load()

    private val _sos = MutableStateFlow(state.toBoard())
    val sos: StateFlow<SosBoard> = _sos.asStateFlow()

    private val _placesChanged = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Family ids whose places changed (from the live connection). */
    val placesChanged: SharedFlow<String> = _placesChanged.asSharedFlow()

    /** Starts listening to the shared live connection. */
    fun listen(scope: CoroutineScope, signals: SharedFlow<LiveSignal>) {
        scope.launch {
            signals.collect { signal ->
                when (signal) {
                    LiveSignal.Connected -> catchUp()
                    is LiveSignal.Event -> when (val e = signal.event) {
                        is LiveEvent.AlertReceived -> receive(listOf(e.alert))
                        is LiveEvent.PlacesChanged -> _placesChanged.tryEmit(e.familyId)
                        else -> Unit
                    }
                }
            }
        }
    }

    /** Alerts delivered live. */
    suspend fun receive(alerts: List<Alert>) = mutex.withLock {
        apply(alerts, initial = false)
    }

    /**
     * `GET /alerts?after=<cursor>` page by page. The first run after sign-in reads from the start
     * (30 days) only to find SOS that are still active. Returns false when the server was not
     * reachable (the caller may retry).
     */
    suspend fun catchUp(): Boolean = mutex.withLock {
        val initial = !state.initialized
        var after = if (initial) 0 else state.lastId
        try {
            repeat(MAX_PAGES) {
                val page = fetch(after, PAGE)
                apply(page.alerts, initial)
                val maxId = page.alerts.maxOfOrNull { it.id } ?: after
                if (page.alerts.size < PAGE || maxId <= after) {
                    if (initial) commit(state.copy(initialized = true))
                    return@withLock true
                }
                after = maxId
            }
            if (initial) commit(state.copy(initialized = true))
            true
        } catch (e: ApiException) {
            Logger.w(TAG, "catch-up failed", e)
            false
        }
    }

    /** My SOS was accepted by the server (response of `POST /sos`). */
    suspend fun ownSosSent(alert: Alert) = mutex.withLock {
        commit(AlertProcessor.ownSosSent(state, alert))
    }

    suspend fun ownSosResolved() = mutex.withLock {
        commit(AlertProcessor.ownSosResolved(state))
    }

    /** Sign-out / account change: forget everything. */
    suspend fun reset() = mutex.withLock {
        store.clear()
        state = AlertState()
        _sos.value = SosBoard()
    }

    /** Drops SOS older than 24 h from the board (called when the UI shows it). */
    suspend fun expireOld() = mutex.withLock {
        commit(AlertProcessor.expire(state, clock()))
    }

    private fun apply(alerts: List<Alert>, initial: Boolean) {
        if (alerts.isEmpty()) return
        val batch = AlertProcessor.process(state, alerts, AlertContext(clock(), ownDeviceId(), initial))
        commit(batch.state)
        if (batch.effects.isNotEmpty()) Logger.i(TAG, "alerts: ${batch.effects.joinToString { it.javaClass.simpleName }}")
        batch.effects.forEach { effect ->
            try {
                handler.handle(effect)
            } catch (e: RuntimeException) {
                Logger.e(TAG, "alert effect failed", e)
            }
        }
    }

    private fun commit(next: AlertState) {
        if (next == state) return
        state = next
        store.save(next)
        _sos.value = next.toBoard()
    }

    private fun AlertState.toBoard() = SosBoard(activeSos.sortedByDescending { it.id }, ownSos)

    companion object {
        private const val TAG = "Alerts"
        const val PAGE = 100
        const val MAX_PAGES = 50
    }
}
