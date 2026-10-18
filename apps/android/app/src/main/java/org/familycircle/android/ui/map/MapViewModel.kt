package org.familycircle.android.ui.map

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.familycircle.android.AppContainer
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.FamilySummary
import org.familycircle.android.api.MapMember
import org.familycircle.android.api.Place
import org.familycircle.android.api.PlaceInput
import org.familycircle.android.live.LiveConnectionState
import org.familycircle.android.live.LiveEffect
import org.familycircle.android.live.LiveEvent
import org.familycircle.android.live.LiveMapReducer
import org.familycircle.android.live.LiveMapState
import org.familycircle.android.live.LiveSignal
import org.familycircle.android.map.LocalFix
import org.familycircle.android.ui.components.UiError
import org.familycircle.android.ui.components.toUiError
import org.familycircle.android.util.Logger

data class MapUiState(
    val families: List<FamilySummary> = emptyList(),
    val familyId: String? = null,
    val live: LiveMapState = LiveMapState.EMPTY,
    val localSelf: LocalFix? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: UiError? = null,
    val lastUpdateMs: Long? = null,
    val selectedUserId: String? = null,
    /** Decoded profile pictures by "userId:version". */
    val avatars: Map<String, Bitmap> = emptyMap(),
    /** Places (geofences) of the shown family. */
    val places: List<Place> = emptyList(),
) {
    val selectedFamily: FamilySummary? get() = families.firstOrNull { it.id == familyId }

    fun avatarFor(member: MapMember): Bitmap? = if (member.hasAvatar) avatars[avatarKey(member.userId, member.avatarVersion)] else null

    companion object {
        fun avatarKey(userId: String, version: Long?) = "$userId:${version ?: 0}"
    }
}

/**
 * Family map: `GET /families/{id}/map` on start, on every WebSocket (re)connect, on `family_changed`
 * and every 60 s as a safety net; `location` / `location_hidden` events are applied immediately via
 * [LiveMapReducer]. Events arriving while a fetch runs are replayed on top of its result so a
 * slightly older snapshot can neither resurrect a hidden marker nor undo a newer position.
 *
 * All state changes happen on the main thread (viewModelScope / composition scope).
 */
class MapViewModel(private val container: AppContainer) : ViewModel() {
    private val api = container.api
    private val prefs = container.prefs

    private val _state = MutableStateFlow(MapUiState(familyId = prefs.current.selectedFamilyId))
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    val liveState: StateFlow<LiveConnectionState> = container.live.state

    private var refreshJob: Job? = null
    private var refreshRequested = false
    private var buffered: MutableList<LiveEvent>? = null
    private val avatarRequests = HashSet<String>()

    init {
        viewModelScope.launch {
            container.recorder.loadLatestQueued()
            container.recorder.latest.collect { fix -> _state.update { it.copy(localSelf = fix) } }
        }
    }

    /**
     * Runs while the map is visible: keeps the shared live connection open (it may already be open
     * for sharing or alerts) + 60 s polling. Cancelled when the screen stops.
     */
    suspend fun runWhileVisible() = coroutineScope {
        requestRefresh()
        launch { container.liveHub.connect(LIVE_HOLDER).collect(::onSignal) }
        launch {
            container.alerts.placesChanged.collect { familyId -> if (familyId == _state.value.familyId) loadPlaces(familyId) }
        }
        launch {
            while (true) {
                delay(POLL_MS)
                requestRefresh()
            }
        }
    }

    fun requestRefresh() {
        refreshRequested = true
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            while (refreshRequested) {
                refreshRequested = false
                load()
            }
        }
    }

    fun selectFamily(id: String) {
        if (id == _state.value.familyId) return
        prefs.setSelectedFamily(id)
        _state.update { it.copy(familyId = id, live = LiveMapState.EMPTY, places = emptyList(), selectedUserId = null, loaded = false) }
        requestRefresh()
    }

    fun select(userId: String?) = _state.update { it.copy(selectedUserId = userId) }

    private fun onSignal(signal: LiveSignal) {
        when (signal) {
            LiveSignal.Connected -> requestRefresh()
            is LiveSignal.Event -> {
                buffered?.add(signal.event)
                val result = LiveMapReducer.reduce(_state.value.live, signal.event)
                _state.update { s ->
                    val hiddenSelected = signal.event is LiveEvent.LocationHidden && s.selectedUserId == signal.event.userId
                    s.copy(live = result.state, selectedUserId = if (hiddenSelected) null else s.selectedUserId)
                }
                if (result.effect != LiveEffect.NONE) requestRefresh()
            }
        }
    }

    private suspend fun load() {
        _state.update { it.copy(loading = true) }
        val events = mutableListOf<LiveEvent>()
        buffered = events
        try {
            val families = api.families()
            val wanted = _state.value.familyId
            val familyId = families.firstOrNull { it.id == wanted }?.id ?: families.firstOrNull()?.id
            if (familyId == null) {
                _state.update {
                    it.copy(families = families, familyId = null, live = LiveMapState.EMPTY, loading = false, loaded = true, error = null)
                }
                return
            }
            val map = api.familyMap(familyId)
            val places = fetchPlaces(familyId)
            val live = LiveMapReducer.replay(LiveMapState.from(map), events)
            _state.update { s ->
                s.copy(
                    families = families,
                    familyId = familyId,
                    places = places ?: s.places.takeIf { s.familyId == familyId }.orEmpty(),
                    live = live,
                    loading = false,
                    loaded = true,
                    error = null,
                    lastUpdateMs = System.currentTimeMillis(),
                    selectedUserId = s.selectedUserId?.takeIf { id -> live.members.any { it.userId == id && it.location != null } || id == selfId(live) },
                )
            }
            loadAvatars(live.members)
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.toUiError()) }
        } finally {
            if (buffered === events) buffered = null
        }
    }

    /** Places are optional (older servers have none): failures only leave the list as it was. */
    private suspend fun loadPlaces(familyId: String) {
        val places = fetchPlaces(familyId) ?: return
        _state.update { if (it.familyId == familyId) it.copy(places = places) else it }
    }

    private suspend fun fetchPlaces(familyId: String): List<Place>? = try {
        api.places(familyId)
    } catch (e: ApiException) {
        Logger.w(TAG, "places not loaded", e)
        null
    }

    /** Long-press on the map → "add place here". */
    suspend fun createPlace(name: String, radiusM: Int, lat: Double, lon: Double): UiError? {
        val familyId = _state.value.familyId ?: return UiError.UNEXPECTED
        return try {
            api.createPlace(familyId, PlaceInput(name.trim(), lat, lon, radiusM))
            loadPlaces(familyId)
            null
        } catch (e: ApiException) {
            e.toUiError()
        }
    }

    private fun selfId(live: LiveMapState) = live.members.firstOrNull { it.isSelf }?.userId

    private fun loadAvatars(members: List<MapMember>) {
        for (m in members) {
            if (!m.hasAvatar) continue
            val key = MapUiState.avatarKey(m.userId, m.avatarVersion)
            if (key in _state.value.avatars || !avatarRequests.add(key)) continue
            viewModelScope.launch {
                val bitmap = container.avatars.load(m.userId, m.avatarVersion)
                if (bitmap != null) _state.update { it.copy(avatars = it.avatars + (key to bitmap)) } else avatarRequests.remove(key)
            }
        }
    }

    private companion object {
        const val TAG = "Map"
        const val LIVE_HOLDER = "map"
        const val POLL_MS = 60_000L
    }
}
