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
import org.familycircle.android.api.MemberLocation
import org.familycircle.android.ui.components.UiError
import org.familycircle.android.ui.components.toUiError

data class MemberRow(
    val userId: String,
    val name: String,
    val color: String,
    val isSelf: Boolean,
    /** Null if this member does not share with me (or has no position yet). */
    val location: MemberLocation?,
    val sharesWithMe: Boolean,
    val iShareWith: Boolean,
)

data class FamilyUiState(
    val families: List<FamilySummary> = emptyList(),
    val selectedFamilyId: String? = null,
    val members: List<MemberRow> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: UiError? = null,
    val lastRefreshMs: Long? = null,
    val shareBusy: Set<String> = emptySet(),
)

class FamilyViewModel(private val container: AppContainer) : ViewModel() {
    private val api = container.api
    private val prefs = container.prefs
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow(FamilyUiState(selectedFamilyId = prefs.current.selectedFamilyId))
    val state: StateFlow<FamilyUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            if (refreshMutex.isLocked) return@launch
            refreshMutex.withLock { load() }
        }
    }

    fun selectFamily(id: String) {
        prefs.setSelectedFamily(id)
        _state.update { it.copy(selectedFamilyId = id, members = emptyList(), loaded = false) }
        refresh()
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
            val (family, map) = kotlinx.coroutines.coroutineScope {
                val f = async { api.family(familyId) }
                val m = async { api.familyMap(familyId) }
                f.await() to m.await()
            }
            val locations = map.members.associateBy { it.userId }
            val selfId = prefs.current.userId
            val rows = family.members.map { m ->
                val mapMember = locations[m.userId]
                MemberRow(
                    userId = m.userId,
                    name = m.displayName,
                    color = m.color,
                    isSelf = mapMember?.isSelf == true || m.userId == selfId,
                    location = mapMember?.location,
                    sharesWithMe = m.sharesWithMe,
                    iShareWith = m.iShareWith,
                )
            }.sortedWith(compareByDescending<MemberRow> { it.isSelf }.thenBy { it.name.lowercase() })
            _state.update {
                it.copy(
                    families = families,
                    selectedFamilyId = familyId,
                    members = rows,
                    loading = false,
                    loaded = true,
                    error = null,
                    lastRefreshMs = System.currentTimeMillis(),
                )
            }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.toUiError()) }
        }
    }

    /** Share / stop sharing my location with [userId] (`PUT`/`DELETE /shares/{userId}`). */
    fun setSharing(userId: String, share: Boolean) {
        if (userId in _state.value.shareBusy) return
        _state.update { s ->
            s.copy(
                shareBusy = s.shareBusy + userId,
                members = s.members.map { if (it.userId == userId) it.copy(iShareWith = share) else it },
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
}
