package org.familycircle.android.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.familycircle.android.AppContainer
import org.familycircle.android.api.Alert
import org.familycircle.android.api.AlertKind
import org.familycircle.android.api.ApiClient
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.HistoryDay
import org.familycircle.android.api.Place
import org.familycircle.android.ui.components.UiError
import org.familycircle.android.ui.components.toUiError
import org.familycircle.android.util.Logger
import org.familycircle.android.util.Rfc3339
import java.time.LocalDate
import java.time.ZoneId

/** Someone whose history I can open: myself, or a person who granted me theirs. */
data class HistoryPerson(val userId: String, val name: String, val self: Boolean)

/** A row of the timeline list: a segment of the day or an event (SOS, check-in, battery). */
sealed interface TimelineRow {
    val t: Long
}

data class SegmentRow(val index: Int, val segment: Segment) : TimelineRow {
    override val t: Long get() = segment.from
}

data class EventRow(val alert: Alert, override val t: Long) : TimelineRow

object HistoryRows {
    /** Alert kinds shown in the timeline (unknown kinds from newer servers are skipped). */
    val KINDS = setOf(AlertKind.SOS, AlertKind.SOS_RESOLVED, AlertKind.CHECK_IN, AlertKind.BATTERY_LOW, AlertKind.LAST_LOCATION)

    /** Segments and events in time order; at the same time the segment comes first. */
    fun of(segments: List<Segment>, events: List<Alert>): List<TimelineRow> {
        val rows = segments.mapIndexed { i, s -> SegmentRow(i, s) } +
            events.filter { it.kind in KINDS }.mapNotNull { a -> Rfc3339.parseMillis(a.createdAt)?.let { EventRow(a, it) } }
        return rows.sortedWith(compareBy<TimelineRow> { it.t }.thenBy { if (it is SegmentRow) 0 else 1 })
    }

    /** Index of the segment that contains [t], if any. */
    fun segmentAt(segments: List<Segment>, t: Long): Int? =
        segments.indexOfFirst { t >= it.from && t <= it.to }.takeIf { it >= 0 }
}

data class HistoryUiState(
    val day: LocalDate,
    val person: HistoryPerson? = null,
    val people: List<HistoryPerson> = emptyList(),
    val peopleLoaded: Boolean = false,
    /** null = not known (settings could not be loaded): try to load. */
    val ownHistoryEnabled: Boolean? = null,
    val points: List<TPoint> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val summary: DaySummary? = null,
    val events: List<Alert> = emptyList(),
    /** Suspect or too vague positions that were left out. */
    val dropped: Int = 0,
    val truncated: Boolean = false,
    /** Mon–Sun of the selected day; null while loading or when not available. */
    val week: List<HistoryDay>? = null,
    val weekMonday: LocalDate? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: UiError? = null,
    val selected: Int? = null,
    val playheadMs: Long? = null,
    val playing: Boolean = false,
) {
    /** My own history is switched off – nothing to load, the screen explains it. */
    val selfOff: Boolean get() = person?.self == true && ownHistoryEnabled == false
    val rows: List<TimelineRow> get() = HistoryRows.of(segments, events)
}

/**
 * The history screen: one day of a person (myself, or someone who shared their history with me)
 * as a timeline of stays, moves and gaps, plus the week overview and a playback position.
 * [initial] is the person to open (from a member's actions); null = the "History" tab picks one.
 */
class HistoryViewModel(container: AppContainer, initial: HistoryPerson?) : ViewModel() {
    private val api = container.api
    private val prefs = container.prefs
    private val zone: ZoneId = ZoneId.systemDefault()
    private val _state = MutableStateFlow(HistoryUiState(day = LocalDate.now(zone), person = initial))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private var places: List<Place> = emptyList()
    private val weekCache = mutableMapOf<String, List<HistoryDay>>()
    private var dayJob: Job? = null
    private var weekJob: Job? = null
    private var playJob: Job? = null

    init {
        if (initial != null) load()
        viewModelScope.launch { loadPeople(initial) }
        viewModelScope.launch {
            places = loadPlaces()
            if (places.isNotEmpty()) rebuild()
        }
    }

    fun today(): LocalDate = LocalDate.now(zone)

    fun selectPerson(person: HistoryPerson) {
        if (person == _state.value.person && _state.value.loaded) return
        _state.update { it.copy(person = person) }
        load()
    }

    fun selectDay(day: LocalDate) {
        val d = minOf(day, today())
        if (d == _state.value.day && _state.value.loaded) return
        _state.update { it.copy(day = d) }
        load()
    }

    fun previousDay() = selectDay(_state.value.day.minusDays(1))

    fun nextDay() = selectDay(_state.value.day.plusDays(1))

    /** Tapped a timeline row: the map shows the segment, the playhead jumps to its start. */
    fun selectSegment(index: Int?) {
        stopPlaying()
        val s = _state.value
        val seg = index?.let { s.segments.getOrNull(it) }
        _state.update { it.copy(selected = if (seg == null) null else index, playheadMs = seg?.from ?: it.playheadMs) }
    }

    /** Tapped an event: playhead to its time, the surrounding segment selected. */
    fun selectTime(t: Long) {
        stopPlaying()
        _state.update { it.copy(playheadMs = t, selected = HistoryRows.segmentAt(it.segments, t)) }
    }

    fun setPlayhead(t: Long) {
        stopPlaying()
        _state.update { it.copy(playheadMs = t) }
    }

    /** Plays the whole day in about [PLAY_DURATION_MS], from the playhead (or the start). */
    fun togglePlay() {
        if (playJob?.isActive == true) {
            stopPlaying()
            return
        }
        val summary = _state.value.summary ?: return
        val first = summary.first ?: return
        val last = summary.last ?: return
        if (last <= first) return
        val step = ((last - first) * FRAME_MS / PLAY_DURATION_MS).coerceAtLeast(1)
        var t = _state.value.playheadMs?.takeIf { it in first until last } ?: first
        _state.update { it.copy(playing = true, playheadMs = t) }
        playJob = viewModelScope.launch {
            while (t < last) {
                delay(FRAME_MS)
                t = minOf(last, t + step)
                _state.update { it.copy(playheadMs = t) }
            }
            _state.update { it.copy(playing = false) }
        }
    }

    private fun stopPlaying() {
        playJob?.cancel()
        playJob = null
        if (_state.value.playing) _state.update { it.copy(playing = false) }
    }

    /** Reload the day and its week (e.g. "today" after a while). */
    fun refresh() {
        val s = _state.value
        val person = s.person ?: return
        weekCache.remove(weekKey(person.userId, TimelineModel.mondayOf(s.day)))
        load()
    }

    private fun load() {
        stopPlaying()
        dayJob?.cancel()
        val s = _state.value
        val person = s.person
        val day = s.day
        _state.update {
            it.copy(
                points = emptyList(), segments = emptyList(), summary = null, events = emptyList(), dropped = 0,
                truncated = false, selected = null, playheadMs = null, error = null, loaded = false,
                loading = person != null && !it.selfOff,
            )
        }
        loadWeek()
        if (person == null || _state.value.selfOff) return
        dayJob = viewModelScope.launch {
            val (from, to) = TimelineModel.dayRange(day, zone)
            try {
                val (raw, events) = coroutineScope {
                    val p = async { api.history(person.userId, from, to) }
                    val e = async { optional { api.historyEvents(person.userId, from, to) }.orEmpty() }
                    p.await() to e.await()
                }
                val pts = TimelineModel.usablePoints(raw)
                val segments = TimelineModel.buildTimeline(pts, places)
                val summary = TimelineModel.summarize(pts, segments)
                _state.update {
                    if (it.person != person || it.day != day) {
                        it
                    } else {
                        it.copy(
                            points = pts, segments = segments, summary = summary, events = events,
                            dropped = raw.size - pts.size, truncated = raw.size >= ApiClient.HISTORY_LIMIT,
                            playheadMs = summary.first, loading = false, loaded = true,
                        )
                    }
                }
            } catch (e: ApiException) {
                _state.update { if (it.person != person || it.day != day) it else it.copy(loading = false, loaded = true, error = e.toUiError()) }
            }
        }
    }

    private fun loadWeek() {
        weekJob?.cancel()
        val s = _state.value
        val person = s.person
        val monday = TimelineModel.mondayOf(s.day)
        if (person == null || s.selfOff) {
            _state.update { it.copy(week = null, weekMonday = monday) }
            return
        }
        val key = weekKey(person.userId, monday)
        weekCache[key]?.let { cached ->
            _state.update { it.copy(week = cached, weekMonday = monday) }
            return
        }
        if (s.weekMonday != monday || s.week == null) _state.update { it.copy(week = null, weekMonday = monday) }
        weekJob = viewModelScope.launch {
            val days = optional { api.historyDays(person.userId, TimelineModel.weekBounds(monday, zone)) } ?: return@launch
            weekCache[key] = days
            _state.update { if (it.person == person && TimelineModel.mondayOf(it.day) == monday) it.copy(week = days, weekMonday = monday) else it }
        }
    }

    /** Recomputes the timeline (places arrived after the positions). */
    private fun rebuild() {
        _state.update {
            if (it.points.isEmpty()) {
                it
            } else {
                val segments = TimelineModel.buildTimeline(it.points, places)
                it.copy(segments = segments, summary = TimelineModel.summarize(it.points, segments), selected = null)
            }
        }
    }

    private suspend fun loadPeople(initial: HistoryPerson?) {
        val (settings, shares) = coroutineScope {
            val st = async { optional { api.settings() } }
            val sh = async { optional { api.shares() } }
            st.await() to sh.await()
        }
        val p = prefs.current
        val self = p.userId?.let { HistoryPerson(it, p.userName.orEmpty(), self = true) }
        val others = shares?.incoming.orEmpty().filter { it.history }.map { HistoryPerson(it.userId, it.displayName, self = false) }
        var people = listOfNotNull(self) + others.sortedBy { it.name.lowercase() }
        if (initial != null && people.none { it.userId == initial.userId }) people = people + initial
        val ownEnabled = settings?.historyEnabled
        _state.update { it.copy(people = people, peopleLoaded = true, ownHistoryEnabled = ownEnabled) }
        if (initial == null) {
            val pick = if (ownEnabled != false && self != null) self else others.firstOrNull() ?: self
            if (pick != null) selectPerson(pick)
        } else if (initial.self && ownEnabled == false) {
            load() // shows the "switched off" notice instead of an empty day
        }
    }

    private suspend fun loadPlaces(): List<Place> = optional {
        coroutineScope {
            api.families().map { f -> async { optional { api.places(f.id) }.orEmpty() } }.flatMap { it.await() }
        }
    }.orEmpty()

    private fun weekKey(userId: String, monday: LocalDate) = "$userId:$monday"

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: ApiException) {
        Logger.w(TAG, "optional part not loaded", e)
        null
    }

    companion object {
        private const val TAG = "History"
        const val PLAY_DURATION_MS = 30_000L
        const val FRAME_MS = 50L
    }
}
