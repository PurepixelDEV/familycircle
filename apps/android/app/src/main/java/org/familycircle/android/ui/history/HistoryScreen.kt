package org.familycircle.android.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.familycircle.android.R
import org.familycircle.android.api.Alert
import org.familycircle.android.api.AlertKind
import org.familycircle.android.api.HistoryDay
import org.familycircle.android.map.MapStyle
import org.familycircle.android.map.MarkerRenderer
import org.familycircle.android.ui.components.LocalAppContainer
import org.familycircle.android.ui.components.UiError
import org.familycircle.android.ui.components.containerViewModel
import org.familycircle.android.ui.components.uiErrorText
import org.familycircle.android.ui.components.windowWidthDp
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Width from which map and timeline sit side by side (tablets, landscape). */
private const val WIDE_MIN_DP = 600

/**
 * History of one day as a timeline: stays (with place names), moves (walk/ride, distance) and gaps,
 * plus SOS / check-in / battery events, a week overview, a day summary and playback on the map.
 * [initial] = the person to show (from a member's actions); null = the "History" tab picks one.
 * [onBack] is null in the tab (no back button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(initial: HistoryPerson?, onBack: (() -> Unit)?, onOpenSettings: () -> Unit) {
    val container = LocalAppContainer.current
    val vm = containerViewModel { HistoryViewModel(it, initial) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val styleUrl = remember(prefs.mapStyleUrl, prefs.serverUrl) { MapStyle.resolve(prefs.mapStyleUrl, prefs.serverUrl) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val today = vm.today()
    val locale: Locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    val hhmm: (Long) -> String = { timeFormat.format(Date(it)) }
    val wide = windowWidthDp() >= WIDE_MIN_DP
    val head = remember(state.points, state.playheadMs) { state.playheadMs?.let { TimelineModel.positionAt(state.points, it) } }
    val selectedSegment = state.selected?.let { state.segments.getOrNull(it) }
    val showDay = state.person != null && !state.selfOff && state.error == null
    // Like the web: no empty map on a day without positions – the list says so instead.
    val showMap = styleUrl != null && showDay && !(state.loaded && state.points.isEmpty())

    val map: @Composable (Modifier) -> Unit = { modifier ->
        if (showMap) {
            Card(modifier) {
                RouteMapView(
                    styleUrl = styleUrl,
                    track = state.points,
                    segments = state.segments,
                    selected = selectedSegment,
                    playhead = head,
                    contentDescription = pluralStringResource(R.plurals.history_map_cd, state.points.size, state.points.size),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    val playback: @Composable () -> Unit = {
        val first = state.summary?.first
        val last = state.summary?.last
        if (showDay && first != null && last != null && last > first) {
            Playback(first, last, state.playheadMs ?: first, state.playing, head, hhmm, vm::togglePlay, vm::setPlayhead)
        }
    }
    val list: LazyListScope.() -> Unit = {
        historyList(state, today, locale, hhmm, vm, onOpenSettings)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(state, onBack)
        DayNav(state.day, today, locale, enabled = state.person != null, onPrevious = vm::previousDay, onNext = vm::nextDay, onToday = { vm.selectDay(today) }, onPick = { picking = true })
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        if (wide && showMap) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    map(Modifier.weight(1f).fillMaxWidth())
                    playback()
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 16.dp), content = list)
            }
        } else {
            // Map outside the list: it keeps its gestures and is not recreated while the list scrolls.
            map(Modifier.fillMaxWidth().height(220.dp))
            playback()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp), content = list)
        }
    }

    if (picking) {
        val zone = ZoneId.systemDefault()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(LocalDate.now(zone))
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    pickerState.selectedDateMillis?.let { ms ->
                        vm.selectDay(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                }) { Text(stringResource(R.string.history_show_day)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun Header(state: HistoryUiState, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_back)) }
        }
        val person = state.person
        Text(
            when {
                person == null -> stringResource(R.string.history_tab_title)
                person.self -> stringResource(R.string.history_title_self)
                else -> stringResource(R.string.history_title, person.name)
            },
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

@Composable
private fun DayNav(
    day: LocalDate,
    today: LocalDate,
    locale: Locale,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onPick: () -> Unit,
) {
    val label = if (day == today) {
        stringResource(R.string.history_today)
    } else {
        day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + ", " + day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onPrevious, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.history_prev_day))
        }
        val pickLabel = stringResource(R.string.history_pick_day)
        TextButton(
            onClick = onPick,
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { stateDescription = pickLabel },
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onNext, enabled = enabled && day < today) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.history_next_day))
        }
        if (day != today) {
            FilterChip(selected = false, onClick = onToday, enabled = enabled, label = { Text(stringResource(R.string.history_today)) })
        }
    }
}

@Composable
private fun Playback(
    first: Long,
    last: Long,
    at: Long,
    playing: Boolean,
    head: Playhead?,
    hhmm: (Long) -> String,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val parts = buildList {
        add(hhmm(at))
        when {
            head == null -> Unit
            head.noData -> add(stringResource(R.string.history_head_no_data))
            else -> {
                head.battery?.let { add(stringResource(R.string.history_head_battery, it)) }
                head.speedKmh?.takeIf { it >= 1 }?.let { add(stringResource(R.string.history_head_speed, it.roundToInt())) }
            }
        }
    }
    val text = parts.joinToString(" · ")
    val sliderLabel = stringResource(R.string.history_time_slider)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (playing) R.string.history_pause else R.string.history_play),
                )
            }
            Slider(
                value = (at - first).toFloat(),
                onValueChange = { onSeek(first + it.toLong()) },
                valueRange = 0f..(last - first).toFloat(),
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = sliderLabel
                    stateDescription = text
                },
            )
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 48.dp))
    }
}

private fun LazyListScope.historyList(
    state: HistoryUiState,
    today: LocalDate,
    locale: Locale,
    hhmm: (Long) -> String,
    vm: HistoryViewModel,
    onOpenSettings: () -> Unit,
) {
    if (state.people.size > 1) {
        item(key = "people") { People(state, vm::selectPerson) }
    }
    val person = state.person
    when {
        state.selfOff -> item(key = "off") { Notice(stringResource(R.string.history_off_self), onOpenSettings) }
        person == null && state.peopleLoaded -> item(key = "none") {
            Notice(
                stringResource(if (state.ownHistoryEnabled == false) R.string.history_off_self else R.string.history_none),
                onOpenSettings.takeIf { state.ownHistoryEnabled == false },
            )
        }
    }
    if (person == null || state.selfOff) return
    item(key = "week") {
        WeekStrip(TimelineModel.mondayOf(state.day), state.week, state.day, today, locale, vm::selectDay)
    }
    state.error?.let { err ->
        item(key = "error") {
            Text(
                if (err == UiError.NOT_FOUND) stringResource(R.string.history_not_available) else uiErrorText(err),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        return
    }
    if (!state.loaded) return
    if (state.points.isEmpty()) {
        item(key = "empty") { Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp)) }
    } else {
        state.summary?.let { s -> item(key = "summary") { Summary(s, locale, hhmm) } }
    }
    val rows = state.rows
    if (rows.isNotEmpty()) {
        item(key = "timeline-h") {
            Text(
                stringResource(R.string.history_timeline),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
            )
        }
        items(rows.size, key = { i -> rows[i].let { r -> if (r is SegmentRow) "s${r.index}" else "e${(r as EventRow).alert.id}" } }) { i ->
            val row = rows[i]
            TimelineRowView(row, first = i == 0, last = i == rows.lastIndex, selected = row is SegmentRow && row.index == state.selected, locale, hhmm) {
                when (row) {
                    is SegmentRow -> vm.selectSegment(if (state.selected == row.index) null else row.index)
                    is EventRow -> vm.selectTime(row.t)
                }
            }
        }
    }
    if (state.dropped > 0 || state.truncated) {
        item(key = "notes") {
            Column(Modifier.padding(top = 8.dp)) {
                if (state.dropped > 0) {
                    Text(pluralStringResource(R.plurals.history_dropped, state.dropped, state.dropped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.truncated) {
                    Text(stringResource(R.string.history_truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun People(state: HistoryUiState, onSelect: (HistoryPerson) -> Unit) {
    val label = stringResource(R.string.history_people)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { contentDescription = label },
    ) {
        state.people.forEach { p ->
            FilterChip(
                selected = p == state.person,
                onClick = { onSelect(p) },
                label = { Text(if (p.self) stringResource(R.string.history_person_me) else p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun Notice(text: String, onOpenSettings: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (onOpenSettings != null) {
                Button(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.history_to_settings)) }
            }
        }
    }
}

@Composable
private fun WeekStrip(monday: LocalDate, week: List<HistoryDay>?, selected: LocalDate, today: LocalDate, locale: Locale, onSelect: (LocalDate) -> Unit) {
    val maxM = week?.maxOfOrNull { it.distanceM }?.takeIf { it > 0 } ?: 1.0
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale) }
    val noData = stringResource(R.string.history_week_no_data)
    val none = stringResource(R.string.history_week_none)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(stringResource(R.string.history_week), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 0 until 7) {
                val day = monday.plusDays(i.toLong())
                val entry = week?.getOrNull(i)
                val enabled = !day.isAfter(today)
                val isSelected = day == selected
                val km = entry?.let { if (it.points == 0) none else TimelineModel.formatDistance(it.distanceM, locale) }.orEmpty()
                val cdValue = entry?.let { if (it.points == 0) noData else TimelineModel.formatDistance(it.distanceM, locale) }.orEmpty()
                val cd = stringResource(
                    R.string.history_week_day_cd,
                    day.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + ", " + day.format(dateFormat),
                    cdValue,
                ).trimEnd(':', ' ')
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable(enabled = enabled, role = Role.Tab) { onSelect(day) }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = cd
                            this.selected = isSelected
                        },
                ) {
                    val faded = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.labelSmall, color = faded, maxLines = 1)
                    Box(Modifier.height(28.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        val frac = ((entry?.distanceM ?: 0.0) / maxM).toFloat().coerceIn(0f, 1f)
                        val h = if ((entry?.points ?: 0) > 0) (28 * frac).coerceAtLeast(3f) else 0f
                        Box(Modifier.width(10.dp).height(h.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
                    }
                    Text(
                        day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = faded,
                    )
                    Text(km, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}

@Composable
private fun Summary(s: DaySummary, locale: Locale, hhmm: (Long) -> String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.history_summary), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat(stringResource(R.string.history_distance), stringResource(R.string.history_distance_value, TimelineModel.formatDistance(s.distanceM, locale)))
                Stat(stringResource(R.string.history_moving), TimelineModel.formatDuration(s.movingMs))
            }
            val counts = buildList {
                add(pluralStringResource(R.plurals.history_stays, s.stays, s.stays))
                if (s.places > 0) add(pluralStringResource(R.plurals.history_places, s.places, s.places))
                if (s.first != null && s.last != null) add(stringResource(R.string.history_span, hhmm(s.first), hhmm(s.last)))
            }
            Text(counts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            if (s.battery.size >= 2 && s.first != null && s.last != null) {
                val lo = s.battery.minOf { it.pct }
                val hi = s.battery.maxOf { it.pct }
                val cd = stringResource(R.string.history_battery_chart_cd, lo, hi)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.history_battery_range, lo, hi), style = MaterialTheme.typography.bodyMedium)
                    BatterySparkline(s.battery, s.first, s.last, Modifier.weight(1f).height(32.dp).semantics { contentDescription = cd })
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun BatterySparkline(samples: List<BatterySample>, first: Long, last: Long, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val span = (last - first).coerceAtLeast(1).toFloat()
        fun x(t: Long) = (t - first) / span * size.width
        fun y(pct: Int) = size.height * (1 - pct / 100f)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        val path = Path()
        samples.forEachIndexed { i, b -> if (i == 0) path.moveTo(x(b.t), y(b.pct)) else path.lineTo(x(b.t), y(b.pct)) }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Colours of the timeline rail. */
private object RailColors {
    val stay = Color(MarkerRenderer.PLACE_GREEN_HEX.toColorInt())
    val move = Color(0xFF2F6FDE)
    val gap = Color(0xFF7B8A8F)
    val battery = Color(0xFFB26A00)
}

@Composable
private fun TimelineRowView(
    row: TimelineRow,
    first: Boolean,
    last: Boolean,
    selected: Boolean,
    locale: Locale,
    hhmm: (Long) -> String,
    onClick: () -> Unit,
) {
    val sos = colorResource(R.color.sos_red)
    val (title, subtitle, dot) = when (row) {
        is SegmentRow -> segmentTexts(row.segment, locale, hhmm)
        is EventRow -> Triple(eventTitle(row.alert), listOfNotNull(hhmm(row.t), row.alert.message).joinToString("\n"), eventColor(row.alert.kind, sos))
    }
    val line = MaterialTheme.colorScheme.outlineVariant
    val hollow = row is SegmentRow && row.segment is Gap
    val small = row is EventRow || (row is SegmentRow && row.segment is Move)
    val surface = MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .semantics { this.selected = selected },
    ) {
        Canvas(Modifier.width(32.dp).heightIn(min = 56.dp).fillMaxHeight()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val w = 2.dp.toPx()
            if (!first) drawLine(line, Offset(cx, 0f), Offset(cx, cy), strokeWidth = w)
            if (!last) drawLine(line, Offset(cx, cy), Offset(cx, size.height), strokeWidth = w)
            val r = (if (small) 5 else 7).dp.toPx()
            if (hollow) {
                drawCircle(surface, r, Offset(cx, cy))
                drawCircle(dot, r, Offset(cx, cy), style = Stroke(width = w))
            } else {
                drawCircle(dot, r, Offset(cx, cy))
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (row is SegmentRow && row.segment is Stay) FontWeight.SemiBold else FontWeight.Normal)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun segmentTexts(seg: Segment, locale: Locale, hhmm: (Long) -> String): Triple<String, String, Color> {
    val span = if (seg.from == seg.to) hhmm(seg.from) else stringResource(R.string.history_span, hhmm(seg.from), hhmm(seg.to))
    val duration = TimelineModel.formatDuration(seg.to - seg.from)
    return when (seg) {
        is Stay -> Triple(
            seg.place?.name ?: stringResource(R.string.history_stay),
            if (seg.from == seg.to) span else "$span · $duration",
            RailColors.stay,
        )
        is Move -> {
            val parts = mutableListOf(span, TimelineModel.formatDistance(seg.distanceM, locale), duration)
            if (seg.mode == MoveMode.RIDE && seg.avgKmh >= 1) parts += stringResource(R.string.history_avg_speed, seg.avgKmh.roundToInt())
            Triple(stringResource(if (seg.mode == MoveMode.RIDE) R.string.history_ride else R.string.history_walk), parts.joinToString(" · "), RailColors.move)
        }
        is Gap -> {
            val parts = mutableListOf(span, duration)
            if (seg.distanceM > 500) parts += stringResource(R.string.history_gap_distance, TimelineModel.formatDistance(seg.distanceM, locale))
            Triple(stringResource(R.string.history_gap), parts.joinToString(" · "), RailColors.gap)
        }
    }
}

@Composable
private fun eventTitle(a: Alert): String = when (a.kind) {
    AlertKind.CHECK_IN -> a.place?.name?.let { stringResource(R.string.history_event_check_in_at, it) } ?: stringResource(R.string.history_event_check_in)
    AlertKind.SOS -> stringResource(R.string.history_event_sos)
    AlertKind.SOS_RESOLVED -> stringResource(R.string.history_event_sos_resolved)
    AlertKind.BATTERY_LOW -> stringResource(R.string.history_event_battery_low, a.batteryPct ?: 0)
    AlertKind.LAST_LOCATION -> stringResource(R.string.history_event_last_location)
    else -> a.kind
}

private fun eventColor(kind: String, sos: Color): Color = when (kind) {
    AlertKind.SOS -> sos
    AlertKind.SOS_RESOLVED, AlertKind.CHECK_IN -> RailColors.stay
    else -> RailColors.battery
}
