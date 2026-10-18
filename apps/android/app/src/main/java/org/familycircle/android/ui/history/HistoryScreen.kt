package org.familycircle.android.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.familycircle.android.R
import org.familycircle.android.map.MapStyle
import org.familycircle.android.ui.components.LocalAppContainer
import org.familycircle.android.ui.components.UiError
import org.familycircle.android.ui.components.containerViewModel
import org.familycircle.android.ui.components.uiErrorText
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import kotlin.math.roundToInt

/**
 * Location history of one day: route on a map + list of times. Own history, or a family member's
 * who allowed it ("darf meinen Verlauf sehen"). Implausible jumps (`suspect`) are left out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(userId: String, name: String, self: Boolean, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm = containerViewModel { HistoryViewModel(it, userId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val styleUrl = remember(prefs.mapStyleUrl, prefs.serverUrl) { MapStyle.resolve(prefs.mapStyleUrl, prefs.serverUrl) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val yesterday = remember(today) { today.minusDays(1) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    // Map outside the list: it keeps its gestures and is not recreated while the list scrolls.
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_back)) }
            }
            Text(
                if (self) stringResource(R.string.history_title_self) else stringResource(R.string.history_title, name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        Column {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.day == today, onClick = { vm.selectDay(today) }, label = { Text(stringResource(R.string.history_today)) })
                FilterChip(selected = state.day == yesterday, onClick = { vm.selectDay(yesterday) }, label = { Text(stringResource(R.string.history_yesterday)) })
                val other = state.day != today && state.day != yesterday
                FilterChip(
                    selected = other,
                    onClick = { picking = true },
                    label = {
                        Text(
                            if (other) state.day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) else stringResource(R.string.history_pick_day),
                        )
                    },
                )
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        state.error?.let { err ->
            Text(
                if (err == UiError.NOT_FOUND) stringResource(R.string.history_not_available) else uiErrorText(err),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (styleUrl != null && state.error == null) {
            Card(Modifier.fillMaxWidth()) {
                RouteMapView(
                    styleUrl = styleUrl,
                    track = state.track,
                    contentDescription = pluralStringResource(R.plurals.history_map_cd, state.track.size, state.track.size),
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (state.loaded && state.error == null) {
            item {
                Text(
                    if (state.track.isEmpty()) {
                        stringResource(R.string.history_empty)
                    } else {
                        pluralStringResource(R.plurals.history_count, state.track.size, state.track.size)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        items(state.track.asReversed(), key = { "${it.atMs}:${it.lat}:${it.lon}" }) { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(timeFormat.format(Date(p.atMs)), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.map_accuracy, p.accuracyM.roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
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
