package org.familycircle.android.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.familycircle.android.AppContainer
import org.familycircle.android.alerts.SosBoard
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.CheckInRequest
import org.familycircle.android.api.PositionDto
import org.familycircle.android.ui.components.UiError
import org.familycircle.android.ui.components.toUiError
import org.familycircle.android.util.Logger

data class SafetyUiState(
    val sendingSos: Boolean = false,
    val resolving: Boolean = false,
    val checkingIn: Boolean = false,
)

sealed interface SafetyMessage {
    data class SosSent(val recipients: Int) : SafetyMessage
    data object Resolved : SafetyMessage
    data class CheckedIn(val recipients: Int, val place: String?) : SafetyMessage

    /** Check-in needs a position, and none could be found. */
    data object CheckInNoFix : SafetyMessage
    data class Failed(val error: UiError) : SafetyMessage
}

/** SOS ("hold 3 s"), "I am safe" and "Arrived safely" – used on the map and the sharing tab. */
class SafetyViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SafetyUiState())
    val state: StateFlow<SafetyUiState> = _state.asStateFlow()

    val sos: StateFlow<SosBoard> = container.alerts.sos

    private val _message = MutableStateFlow<SafetyMessage?>(null)
    val message: StateFlow<SafetyMessage?> = _message.asStateFlow()

    init {
        viewModelScope.launch { container.alerts.expireOld() }
    }

    /** After the 3-second hold: a fresh fix if one comes within 5 s, otherwise the server uses the latest. */
    fun sendSos() {
        if (_state.value.sendingSos) return
        _state.update { it.copy(sendingSos = true) }
        // Process scope: leaving the screen must not cancel an SOS that is being sent.
        container.scope.launch {
            try {
                val fix = container.tracking.freshFix(SOS_FIX_TIMEOUT_MS)
                val created = container.api.sendSos(fix?.let { PositionDto(it.lat, it.lon, it.accuracyM) })
                container.alerts.ownSosSent(created.alert)
                Logger.i(TAG, "sos sent (with position: ${fix != null})")
                _message.value = SafetyMessage.SosSent(created.recipients)
            } catch (e: ApiException) {
                Logger.w(TAG, "sos failed", e)
                _message.value = SafetyMessage.Failed(e.toUiError())
            } finally {
                _state.update { it.copy(sendingSos = false) }
            }
        }
    }

    /** "Ich bin in Sicherheit". */
    fun resolveSos() {
        if (_state.value.resolving) return
        _state.update { it.copy(resolving = true) }
        viewModelScope.launch {
            try {
                container.api.resolveSos()
                container.alerts.ownSosResolved()
                _message.value = SafetyMessage.Resolved
            } catch (e: ApiException) {
                _message.value = SafetyMessage.Failed(e.toUiError())
            } finally {
                _state.update { it.copy(resolving = false) }
            }
        }
    }

    /** "Bin angekommen" with an optional message; needs a fresh position. */
    fun checkIn(message: String) {
        if (_state.value.checkingIn) return
        _state.update { it.copy(checkingIn = true) }
        viewModelScope.launch {
            try {
                val fix = container.tracking.freshFix(CHECK_IN_FIX_TIMEOUT_MS, maxAgeMs = CHECK_IN_MAX_AGE_MS)
                if (fix == null) {
                    _message.value = SafetyMessage.CheckInNoFix
                    return@launch
                }
                val text = message.trim().take(MAX_MESSAGE).ifEmpty { null }
                val created = container.api.checkIn(CheckInRequest(fix.lat, fix.lon, fix.accuracyM, text))
                _message.value = SafetyMessage.CheckedIn(created.recipients, created.alert.place?.name)
            } catch (e: ApiException) {
                _message.value = SafetyMessage.Failed(e.toUiError())
            } finally {
                _state.update { it.copy(checkingIn = false) }
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        private const val TAG = "Safety"
        const val SOS_FIX_TIMEOUT_MS = 5_000L
        const val CHECK_IN_FIX_TIMEOUT_MS = 30_000L
        const val CHECK_IN_MAX_AGE_MS = 2 * 60_000L
        const val MAX_MESSAGE = 140
    }
}
