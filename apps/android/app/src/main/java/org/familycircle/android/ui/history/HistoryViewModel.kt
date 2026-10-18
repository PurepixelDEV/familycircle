package org.familycircle.android.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.familycircle.android.AppContainer
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.HistoryPoint
import org.familycircle.android.ui.components.UiError
import org.familycircle.android.ui.components.toUiError
import org.familycircle.android.util.Rfc3339
import java.time.LocalDate
import java.time.ZoneId

/** Pure helpers for the history screen (unit-tested). */
object HistoryModel {
    /** Local day → `[from, to)` in epoch millis (handles daylight saving: days of 23/25 h). */
    fun dayRange(day: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return from to to
    }

    /**
     * Points to draw, oldest first: `suspect` points (implausible GPS jumps flagged by the server)
     * and points without a valid timestamp are skipped.
     */
    fun track(points: List<HistoryPoint>): List<TrackPoint> = points
        .asSequence()
        .filterNot { it.suspect }
        .mapNotNull { p -> Rfc3339.parseMillis(p.recordedAt)?.let { TrackPoint(it, p.lat, p.lon, p.accuracyM) } }
        .sortedBy { it.atMs }
        .toList()
}

data class TrackPoint(val atMs: Long, val lat: Double, val lon: Double, val accuracyM: Double) {
    override fun toString(): String = "TrackPoint($atMs)"
}

data class HistoryUiState(
    val day: LocalDate,
    val track: List<TrackPoint> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: UiError? = null,
)

/** History of one day for [userId] (myself, or someone who shared their history with me). */
class HistoryViewModel(private val container: AppContainer, private val userId: String) : ViewModel() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val _state = MutableStateFlow(HistoryUiState(day = LocalDate.now(zone)))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun selectDay(day: LocalDate) {
        if (day == _state.value.day && _state.value.loaded) return
        _state.update { it.copy(day = day, track = emptyList(), loaded = false) }
        load()
    }

    fun load() {
        job?.cancel()
        val day = _state.value.day
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val (from, to) = HistoryModel.dayRange(day, zone)
                val points = container.api.history(userId, from, to)
                _state.update { if (it.day == day) it.copy(track = HistoryModel.track(points), loading = false, loaded = true) else it }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, loaded = true, error = e.toUiError()) }
            }
        }
    }
}
