package org.familycircle.android.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.familycircle.android.AppContainer
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.FamilySummary
import org.familycircle.android.api.InviteCreate
import org.familycircle.android.api.InviteLinks
import org.familycircle.android.api.MemberLocation
import org.familycircle.android.api.Place
import org.familycircle.android.api.PlaceInput
import org.familycircle.android.api.PrivacySettings
import org.familycircle.android.api.Shares
import org.familycircle.android.ui.components.UiError
import org.familycircle.android.ui.components.toUiError
import org.familycircle.android.util.Logger

data class MemberRow(
    val userId: String,
    val name: String,
    val color: String,
    val isSelf: Boolean,
    /** Null if this member does not share with me (or has no position yet). */
    val location: MemberLocation?,
    val sharesWithMe: Boolean,
    val iShareWith: Boolean,
    /** I let this member see my history (outgoing share with `history`). */
    val seesMyHistory: Boolean = false,
    /** I can open this member's history (incoming share with `history`). */
    val historyVisible: Boolean = false,
)

/** Remote actions for a member (docs/ALERTS.md "Fernaktionen"). */
enum class RemoteAction { RING, LOCATE }

sealed interface FamilyMessage {
    /** Request accepted; [online] = a phone of the person is connected right now. */
    data class RequestSent(val action: RemoteAction, val name: String, val online: Boolean, val self: Boolean) : FamilyMessage

    /** 403: the person did not allow it. */
    data class NotAllowed(val name: String) : FamilyMessage
    data object PlaceSaved : FamilyMessage
    data object PlaceDeleted : FamilyMessage

    /** A new place needs my current position and none could be found. */
    data object NoFix : FamilyMessage

    /** 409: the family already has 50 places. */
    data object TooManyPlaces : FamilyMessage
    data class Failed(val error: UiError) : FamilyMessage
}

data class FamilyUiState(
    val families: List<FamilySummary> = emptyList(),
    val selectedFamilyId: String? = null,
    val members: List<MemberRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: UiError? = null,
    val lastRefreshMs: Long? = null,
    val shareBusy: Set<String> = emptySet(),
    /** Last invite created on this screen (memory only, never persisted); shared right away. */
    val invite: SharedInvite? = null,
    val inviting: Boolean = false,
    /** One-shot: open the share sheet for [invite] (cleared by [FamilyViewModel.shareHandled]). */
    val shareRequested: Boolean = false,
    /** My own `history_enabled`; null until loaded. */
    val myHistoryEnabled: Boolean? = null,
    val places: List<Place> = emptyList(),
    val placeBusy: Boolean = false,
    /** Member ids with a ring/locate request in flight. */
    val requestBusy: Set<String> = emptySet(),
) {
    val selectedFamily: FamilySummary? get() = families.firstOrNull { it.id == selectedFamilyId }

    /** Only admins can invite (the server enforces it as well). */
    val canInvite: Boolean get() = selectedFamily?.role == "admin"

    val isAdmin: Boolean get() = selectedFamily?.role == "admin"
}

/** A freshly created invite with everything needed for the share message. */
data class SharedInvite(val familyName: String, val link: String, val code: String, val expiresAt: String) {
    override fun toString(): String = "SharedInvite($familyName, <code>, $expiresAt)"
}

class FamilyViewModel(private val container: AppContainer) : ViewModel() {
    private val api = container.api
    private val prefs = container.prefs
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow(FamilyUiState(selectedFamilyId = prefs.current.selectedFamilyId))
    val state: StateFlow<FamilyUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<FamilyMessage?>(null)
    val message: StateFlow<FamilyMessage?> = _message.asStateFlow()

    val selfId: String? get() = prefs.current.userId

    init {
        // Places changed elsewhere (another phone, the web app) while the connection is open.
        viewModelScope.launch {
            container.alerts.placesChanged.collect { id -> if (id == _state.value.selectedFamilyId) refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (refreshMutex.isLocked) return@launch
            refreshMutex.withLock { load() }
        }
    }

    fun selectFamily(id: String) {
        prefs.setSelectedFamily(id)
        _state.update { it.copy(selectedFamilyId = id, members = emptyList(), places = emptyList(), loaded = false) }
        refresh()
    }

    fun messageShown() {
        _message.value = null
    }

    private suspend fun load() {
        _state.update { it.copy(loading = true) }
        try {
            val families = api.families()
            val wanted = _state.value.selectedFamilyId
            val familyId = families.firstOrNull { it.id == wanted }?.id ?: families.firstOrNull()?.id
            if (familyId == null) {
                _state.update { it.copy(families = families, members = emptyList(), loading = false, loaded = true, error = null) }
                return
            }
            val loaded = kotlinx.coroutines.coroutineScope {
                val f = async { api.family(familyId) }
                val m = async { api.familyMap(familyId) }
                // Optional parts (older servers, transient errors): the member list works without them.
                val s = async { optional { api.shares() } }
                val st = async { optional { api.settings() } }
                val p = async { optional { api.places(familyId) } }
                Loaded(f.await(), m.await(), s.await(), st.await(), p.await())
            }
            val locations = loaded.map.members.associateBy { it.userId }
            val outgoingHistory = loaded.shares?.outgoing?.filter { it.history }?.map { it.userId }?.toSet().orEmpty()
            val incomingHistory = loaded.shares?.incoming?.filter { it.history }?.map { it.userId }?.toSet().orEmpty()
            val selfId = prefs.current.userId
            val rows = loaded.family.members.map { m ->
                val mapMember = locations[m.userId]
                MemberRow(
                    userId = m.userId,
                    name = m.displayName,
                    color = m.color,
                    isSelf = mapMember?.isSelf == true || m.userId == selfId,
                    location = mapMember?.location,
                    sharesWithMe = m.sharesWithMe,
                    iShareWith = m.iShareWith,
                    seesMyHistory = m.userId in outgoingHistory,
                    historyVisible = m.userId in incomingHistory,
                )
            }.sortedWith(compareByDescending<MemberRow> { it.isSelf }.thenBy { it.name.lowercase() })
            loaded.settings?.let { prefs.setShareBattery(it.shareBattery) }
            _state.update {
                it.copy(
                    families = families,
                    selectedFamilyId = familyId,
                    members = rows,
                    loading = false,
                    loaded = true,
                    error = null,
                    lastRefreshMs = System.currentTimeMillis(),
                    myHistoryEnabled = loaded.settings?.historyEnabled ?: it.myHistoryEnabled,
                    places = loaded.places ?: it.places,
                )
            }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.toUiError()) }
        }
    }

    private class Loaded(
        val family: org.familycircle.android.api.Family,
        val map: org.familycircle.android.api.FamilyMap,
        val shares: Shares?,
        val settings: PrivacySettings?,
        val places: List<Place>?,
    )

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: ApiException) {
        Logger.w(TAG, "optional part not loaded", e)
        null
    }

    /** Creates a single-use invite (72 h) for the selected family; the screen opens the share sheet. */
    fun createInvite() {
        val family = _state.value.selectedFamily ?: return
        val server = prefs.current.serverUrl ?: return
        if (_state.value.inviting) return
        _state.update { it.copy(inviting = true, error = null) }
        viewModelScope.launch {
            try {
                val created = api.createInvite(family.id, InviteCreate(expiresInHours = INVITE_HOURS, maxUses = 1))
                val invite = SharedInvite(family.name, InviteLinks.registerUrl(server, created.code), created.code, created.expiresAt)
                _state.update { it.copy(inviting = false, invite = invite, shareRequested = true) }
            } catch (e: ApiException) {
                _state.update { it.copy(inviting = false, error = e.toUiError()) }
            }
        }
    }

    fun shareAgain() = _state.update { if (it.invite != null) it.copy(shareRequested = true) else it }

    fun shareHandled() = _state.update { it.copy(shareRequested = false) }

    /** Share / stop sharing my location with [userId] (`PUT`/`DELETE /shares/{userId}`). */
    fun setSharing(userId: String, share: Boolean) {
        if (userId in _state.value.shareBusy) return
        _state.update { s ->
            s.copy(
                shareBusy = s.shareBusy + userId,
                members = s.members.map {
                    if (it.userId == userId) it.copy(iShareWith = share, seesMyHistory = it.seesMyHistory && share) else it
                },
            )
        }
        viewModelScope.launch {
            try {
                if (share) api.grantShare(userId) else api.revokeShare(userId)
                _state.update { it.copy(shareBusy = it.shareBusy - userId, error = null) }
            } catch (e: ApiException) {
                _state.update { s ->
                    s.copy(
                        shareBusy = s.shareBusy - userId,
                        members = s.members.map { if (it.userId == userId) it.copy(iShareWith = !share) else it },
                        error = e.toUiError(),
                    )
                }
            }
        }
    }

    /** "darf meinen Verlauf sehen" (`PATCH /shares/{userId}`). */
    fun setHistoryGrant(userId: String, allow: Boolean) {
        if (userId in _state.value.shareBusy) return
        _state.update { s ->
            s.copy(shareBusy = s.shareBusy + userId, members = s.members.map { if (it.userId == userId) it.copy(seesMyHistory = allow) else it })
        }
        viewModelScope.launch {
            try {
                api.setShareHistory(userId, allow)
                _state.update { it.copy(shareBusy = it.shareBusy - userId) }
            } catch (e: ApiException) {
                _state.update { s ->
                    s.copy(
                        shareBusy = s.shareBusy - userId,
                        members = s.members.map { if (it.userId == userId) it.copy(seesMyHistory = !allow) else it },
                    )
                }
                _message.value = FamilyMessage.Failed(e.toUiError())
            }
        }
    }

    /** Ring / locate a member (or, for myself, my other devices). */
    fun remote(member: MemberRow, action: RemoteAction) {
        if (member.userId in _state.value.requestBusy) return
        _state.update { it.copy(requestBusy = it.requestBusy + member.userId) }
        viewModelScope.launch {
            try {
                val result = when (action) {
                    RemoteAction.RING -> api.ring(member.userId)
                    RemoteAction.LOCATE -> api.locate(member.userId)
                }
                _message.value = FamilyMessage.RequestSent(action, member.name, result.online, member.isSelf)
                if (action == RemoteAction.LOCATE) refreshSoon()
            } catch (e: ApiException) {
                _message.value = if (e is ApiException.Server && e.status == 403) {
                    FamilyMessage.NotAllowed(member.name)
                } else {
                    FamilyMessage.Failed(e.toUiError())
                }
            } finally {
                _state.update { it.copy(requestBusy = it.requestBusy - member.userId) }
            }
        }
    }

    /** The answer to a locate request arrives as a new position within seconds. */
    private fun refreshSoon() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(LOCATE_REFRESH_MS)
            refresh()
        }
    }

    // ---------------------------------------------------------------- places

    /** New place at my current position. */
    fun addPlace(name: String, radiusM: Int) {
        val familyId = _state.value.selectedFamilyId ?: return
        placeAction {
            val fix = container.tracking.freshFix(PLACE_FIX_TIMEOUT_MS, maxAgeMs = PLACE_FIX_MAX_AGE_MS)
            if (fix == null) {
                _message.value = FamilyMessage.NoFix
                return@placeAction
            }
            api.createPlace(familyId, PlaceInput(name.trim(), fix.lat, fix.lon, radiusM))
            _message.value = FamilyMessage.PlaceSaved
        }
    }

    /** Rename / resize, optionally move to my current position. */
    fun updatePlace(place: Place, name: String, radiusM: Int, moveToMe: Boolean) {
        placeAction {
            var lat: Double? = null
            var lon: Double? = null
            if (moveToMe) {
                val fix = container.tracking.freshFix(PLACE_FIX_TIMEOUT_MS, maxAgeMs = PLACE_FIX_MAX_AGE_MS)
                if (fix == null) {
                    _message.value = FamilyMessage.NoFix
                    return@placeAction
                }
                lat = fix.lat
                lon = fix.lon
            }
            val trimmed = name.trim()
            api.updatePlace(
                place.id,
                PlaceInput(
                    name = trimmed.takeIf { it != place.name },
                    lat = lat,
                    lon = lon,
                    radiusM = radiusM.takeIf { it != place.radiusM },
                ),
            )
            _message.value = FamilyMessage.PlaceSaved
        }
    }

    fun deletePlace(place: Place) = placeAction {
        api.deletePlace(place.id)
        _message.value = FamilyMessage.PlaceDeleted
    }

    /** "Meldungen" switch: muted = no arrival/departure notifications of this place for me. */
    fun setPlaceAlerts(place: Place, alertsOn: Boolean) {
        _state.update { s -> s.copy(places = s.places.map { if (it.id == place.id) it.copy(muted = !alertsOn) else it }) }
        viewModelScope.launch {
            try {
                api.setPlaceMuted(place.id, muted = !alertsOn)
            } catch (e: ApiException) {
                _state.update { s -> s.copy(places = s.places.map { if (it.id == place.id) it.copy(muted = alertsOn) else it }) }
                _message.value = FamilyMessage.Failed(e.toUiError())
            }
        }
    }

    /** Creator or family admin may edit/delete (the server checks it as well). */
    fun canEdit(place: Place): Boolean = _state.value.isAdmin || (place.createdBy != null && place.createdBy == prefs.current.userId)

    private fun placeAction(block: suspend () -> Unit) {
        if (_state.value.placeBusy) return
        _state.update { it.copy(placeBusy = true) }
        viewModelScope.launch {
            try {
                block()
                load()
            } catch (e: ApiException) {
                _message.value = if (e is ApiException.Server && e.status == 409) FamilyMessage.TooManyPlaces else FamilyMessage.Failed(e.toUiError())
            } finally {
                _state.update { it.copy(placeBusy = false) }
            }
        }
    }

    private companion object {
        const val TAG = "Family"
        const val INVITE_HOURS = 72
        const val LOCATE_REFRESH_MS = 8_000L
        const val PLACE_FIX_TIMEOUT_MS = 30_000L
        const val PLACE_FIX_MAX_AGE_MS = 2 * 60_000L
    }
}
