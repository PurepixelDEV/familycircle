package org.familycircle.android.ui.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.familycircle.android.R
import org.familycircle.android.api.Place
import org.familycircle.android.api.PlaceInput
import org.familycircle.android.ui.components.SectionTitle
import org.familycircle.android.ui.components.SwitchRow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Slider position (0…1) ↔ radius in metres (50…2000). Quadratic, so the common small radii
 * (50–300 m) get most of the slider; rounded to 10 m (pure, unit-tested).
 */
object RadiusScale {
    private const val MIN = PlaceInput.MIN_RADIUS_M.toFloat()
    private const val MAX = PlaceInput.MAX_RADIUS_M.toFloat()

    fun toRadius(position: Float): Int {
        val p = position.coerceIn(0f, 1f)
        val raw = MIN + (MAX - MIN) * p * p
        return ((raw / 10f).roundToInt() * 10).coerceIn(PlaceInput.MIN_RADIUS_M, PlaceInput.MAX_RADIUS_M)
    }

    fun toPosition(radiusM: Int): Float {
        val r = radiusM.toFloat().coerceIn(MIN, MAX)
        return sqrt((r - MIN) / (MAX - MIN))
    }
}

/**
 * Add / edit a place: name, radius slider (50–2000 m, default 150 m) and where the centre is.
 * @param initial the place being edited, null for a new one.
 * @param positionNote explains the centre ("your current position", "where you pressed").
 * @param allowMove edit only: offer "move to my current position".
 */
@Composable
fun PlaceDialog(
    title: String,
    initial: Place?,
    positionNote: String?,
    busy: Boolean,
    onConfirm: (name: String, radiusM: Int, moveToMe: Boolean) -> Unit,
    onDismiss: () -> Unit,
    allowMove: Boolean = false,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var position by rememberSaveable { mutableFloatStateOf(RadiusScale.toPosition(initial?.radiusM ?: PlaceInput.DEFAULT_RADIUS_M)) }
    var moveToMe by rememberSaveable { mutableStateOf(false) }
    val radius = RadiusScale.toRadius(position)
    val nameOk = name.trim().length in 1..PlaceInput.MAX_NAME
    val radiusText = stringResource(R.string.place_radius_value, radius)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(PlaceInput.MAX_NAME) },
                    label = { Text(stringResource(R.string.place_name)) },
                    placeholder = { Text(stringResource(R.string.place_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.place_radius, radius), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = position,
                    onValueChange = { position = it },
                    modifier = Modifier.semantics { stateDescription = radiusText },
                )
                Text(
                    stringResource(R.string.place_radius_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (positionNote != null) Text(positionNote, style = MaterialTheme.typography.bodyMedium)
                if (allowMove) {
                    SwitchRow(
                        title = stringResource(R.string.place_move_to_me),
                        description = null,
                        checked = moveToMe,
                        onCheckedChange = { moveToMe = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, radius, moveToMe) }, enabled = nameOk && !busy) {
                Text(stringResource(R.string.place_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Places of the family: who is there, alerts on/off, edit/delete for creator and admins. */
@Composable
fun PlacesSection(
    places: List<Place>,
    memberNames: Map<String, String>,
    busy: Boolean,
    canEdit: (Place) -> Boolean,
    onAdd: () -> Unit,
    onEdit: (Place) -> Unit,
    onDelete: (Place) -> Unit,
    onAlertsChange: (Place, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.places_title))
        Text(stringResource(R.string.places_intro), style = MaterialTheme.typography.bodyMedium)
        if (places.isEmpty()) {
            Text(stringResource(R.string.places_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        places.forEach { place ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.place_radius_value, place.radiusM), style = MaterialTheme.typography.bodyMedium)
                    val present = place.present.mapNotNull { memberNames[it] }
                    Text(
                        if (present.isEmpty()) {
                            stringResource(R.string.place_nobody_there)
                        } else {
                            pluralStringResource(R.plurals.place_there, present.size, present.joinToString(", "))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SwitchRow(
                        title = stringResource(R.string.place_alerts),
                        description = stringResource(R.string.place_alerts_desc),
                        checked = !place.muted,
                        onCheckedChange = { onAlertsChange(place, it) },
                    )
                    if (canEdit(place)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onEdit(place) }, enabled = !busy) { Text(stringResource(R.string.place_edit)) }
                            TextButton(onClick = { onDelete(place) }, enabled = !busy) { Text(stringResource(R.string.place_delete)) }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.place_add))
        }
    }
}
