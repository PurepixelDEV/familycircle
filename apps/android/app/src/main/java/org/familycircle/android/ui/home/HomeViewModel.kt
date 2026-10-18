package org.familycircle.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.familycircle.android.AppContainer
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.PrivacySettingsUpdate
import org.familycircle.android.data.prefs.AppPrefs
import org.familycircle.android.data.prefs.TrackingProblem
import org.familycircle.android.tracking.TrackingController
import org.familycircle.android.tracking.TrackingMode
import org.familycircle.android.tracking.TrackingRuntime
import org.familycircle.android.ui.components.UiError
import org.familycircle.android.ui.components.toUiError

data class HomeUiState(
    val prefs: AppPrefs = AppPrefs(),
    val queueSize: Int = 0,
    val running: Boolean = false,
    val effectiveMode: TrackingMode? = null,
    val sendingNow: Boolean = false,
    /** `sharing_paused` from the server; null until loaded. */
    val sharingPaused: Boolean? = null,
) {
    val status: TrackingStatus get() = TrackingStatus.derive(running, prefs.trackingDesired, prefs.trackingPausedByUser)
}

sealed interface HomeMessage {
    data class Sent(val uploaded: Int) : HomeMessage
    data object Queued : HomeMessage
    data object NoFix : HomeMessage
    data class Problem(val problem: TrackingProblem) : HomeMessage
    data class Error(val error: UiError) : HomeMessage
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val tracking = container.tracking
    private val sharingPaused = MutableStateFlow<Boolean?>(null)

    private val _message = MutableStateFlow<HomeMessage?>(null)
    val message: StateFlow<HomeMessage?> = _message.asStateFlow()

    val state: StateFlow<HomeUiState> = combine(
        combine(container.prefs.state, container.dao.countFlow(), TrackingRuntime.running, ::Triple),
        TrackingRuntime.effectiveMode,
        tracking.sendNowRunning,
        sharingPaused,
    ) { (prefs, queue, running), effective, sending, paused ->
        HomeUiState(prefs, queue, running, effective, sending, paused)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refreshServerSettings()
    }

    fun refreshServerSettings() {
        viewModelScope.launch {
            try {
                val settings = container.api.settings()
                sharingPaused.value = settings.sharingPaused
                container.prefs.setShareBattery(settings.shareBattery)
            } catch (_: ApiException) {
                // Not critical for this screen; the status card works without it.
            }
        }
    }

    fun hasLocationPermission(): Boolean = tracking.hasForegroundPermission()

    fun start() {
        val result = tracking.start(container.prefs.current.trackingMode)
        if (result is TrackingController.StartResult.Failed) _message.value = HomeMessage.Problem(result.problem)
    }

    fun stop() = tracking.stop()

    fun selectMode(mode: TrackingMode) = tracking.setMode(mode)

    fun sendNow() {
        viewModelScope.launch {
            _message.value = when (val r = tracking.sendNow()) {
                is TrackingController.SendNowResult.Done ->
                    if (r.upload.success) HomeMessage.Sent(r.upload.uploaded) else HomeMessage.Queued
                TrackingController.SendNowResult.NoFix -> HomeMessage.NoFix
                is TrackingController.SendNowResult.Failed -> HomeMessage.Problem(r.problem)
            }
        }
    }

    fun resumeSharingOnServer() {
        viewModelScope.launch {
            try {
                sharingPaused.value = container.api.updateSettings(PrivacySettingsUpdate(sharingPaused = false)).sharingPaused
            } catch (e: ApiException) {
                _message.value = HomeMessage.Error(e.toUiError())
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }
}
