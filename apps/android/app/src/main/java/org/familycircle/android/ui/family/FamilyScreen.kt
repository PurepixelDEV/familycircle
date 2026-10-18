package org.familycircle.android.ui.family

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.familycircle.android.R
import org.familycircle.android.api.MemberLocation
import org.familycircle.android.api.Place
import org.familycircle.android.ui.components.FreshnessBadge
import org.familycircle.android.ui.components.SwitchRow
import org.familycircle.android.ui.components.motionText
import org.familycircle.android.ui.components.parseMemberColor
import org.familycircle.android.ui.components.containerViewModel
import org.familycircle.android.ui.components.relativeAgeText
import org.familycircle.android.ui.components.rememberNow
import org.familycircle.android.ui.components.uiErrorText
import org.familycircle.android.ui.theme.LocalStatusColors
import org.familycircle.android.util.Rfc3339
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private const val AUTO_REFRESH_MS = 30_000L

@Composable
fun FamilyScreen(snackbar: SnackbarHostState, onOpenHistory: (userId: String, name: String, self: Boolean) -> Unit) {
    val vm = containerViewModel { FamilyViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val now by rememberNow(10_000)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val noMapsApp = stringResource(R.string.family_no_maps_app)
    var ringTarget by remember { mutableStateOf<MemberRow?>(null) }
    var placeDialog by remember { mutableStateOf<PlaceDialogMode?>(null) }
    var deleteTarget by remember { mutableStateOf<Place?>(null) }

    val messageText = message?.let { familyMessageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            vm.messageShown()
            snackbar.showSnackbar(messageText)
        }
    }

    LaunchedEffect(state.shareRequested) {
        val invite = state.invite
        if (state.shareRequested && invite != null) {
            shareInvite(context, invite)
            vm.shareHandled()
        }
    }

    // Auto-refresh every 30 s, only while the screen is visible.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                delay(AUTO_REFRESH_MS)
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.family_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::refresh, enabled = !state.loading) { Text(stringResource(R.string.action_refresh)) }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.families.size > 1) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.families.forEach { f ->
                        FilterChip(
                            selected = f.id == state.selectedFamilyId,
                            onClick = { vm.selectFamily(f.id) },
                            label = { Text(f.name) },
                        )
                    }
                }
            }
        }
        state.error?.let { err ->
            item { Text(uiErrorText(err), color = MaterialTheme.colorScheme.error) }
        }
        if (state.loaded && state.families.isEmpty()) {
            item { Text(stringResource(R.string.family_none), style = MaterialTheme.typography.bodyLarge) }
        }
        state.lastRefreshMs?.let { at ->
            item {
                Text(
                    stringResource(R.string.family_updated, relativeAgeText(at, now)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.canInvite) {
            item {
                InviteCard(state.invite, state.inviting, onCreate = vm::createInvite, onShareAgain = vm::shareAgain)
            }
        }
        items(state.members, key = { it.userId }) { member ->
            MemberCard(
                member = member,
                now = now,
                shareBusy = member.userId in state.shareBusy,
                requestBusy = member.userId in state.requestBusy,
                myHistoryEnabled = state.myHistoryEnabled,
                onShareChange = { vm.setSharing(member.userId, it) },
                onHistoryGrantChange = { vm.setHistoryGrant(member.userId, it) },
                onLocate = { vm.remote(member, RemoteAction.LOCATE) },
                onRing = { ringTarget = member },
                onHistory = { onOpenHistory(member.userId, member.name, member.isSelf) },
                onOpenMap = { loc ->
                    val label = Uri.encode(member.name)
                    val uri = "geo:${loc.lat},${loc.lon}?q=${loc.lat},${loc.lon}($label)".toUri()
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar(noMapsApp) }
                    }
                },
            )
        }
        if (state.selectedFamilyId != null && state.loaded) {
            item {
                PlacesSection(
                    places = state.places,
                    memberNames = state.members.associate { it.userId to it.name },
                    busy = state.placeBusy,
                    canEdit = vm::canEdit,
                    onAdd = { placeDialog = PlaceDialogMode.Add },
                    onEdit = { placeDialog = PlaceDialogMode.Edit(it) },
                    onDelete = { deleteTarget = it },
                    onAlertsChange = vm::setPlaceAlerts,
                )
            }
        }
    }

    ringTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { ringTarget = null },
            title = { Text(stringResource(if (member.isSelf) R.string.ring_self_confirm_title else R.string.ring_confirm_title, member.name)) },
            text = { Text(stringResource(if (member.isSelf) R.string.ring_self_confirm_body else R.string.ring_confirm_body, member.name)) },
            confirmButton = {
                TextButton(onClick = {
                    ringTarget = null
                    vm.remote(member, RemoteAction.RING)
                }) { Text(stringResource(R.string.ring_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { ringTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    when (val mode = placeDialog) {
        PlaceDialogMode.Add -> PlaceDialog(
            title = stringResource(R.string.place_add),
            initial = null,
            positionNote = stringResource(R.string.place_position_me),
            busy = state.placeBusy,
            onConfirm = { name, radius, _ ->
                placeDialog = null
                vm.addPlace(name, radius)
            },
            onDismiss = { placeDialog = null },
        )
        is PlaceDialogMode.Edit -> PlaceDialog(
            title = stringResource(R.string.place_edit_title),
            initial = mode.place,
            positionNote = null,
            busy = state.placeBusy,
            allowMove = true,
            onConfirm = { name, radius, move ->
                placeDialog = null
                vm.updatePlace(mode.place, name, radius, move)
            },
            onDismiss = { placeDialog = null },
        )
        null -> Unit
    }

    deleteTarget?.let { place ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.place_delete_title, place.name)) },
            text = { Text(stringResource(R.string.place_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    vm.deletePlace(place)
                }) { Text(stringResource(R.string.place_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private sealed interface PlaceDialogMode {
    data object Add : PlaceDialogMode
    data class Edit(val place: Place) : PlaceDialogMode
}

@Composable
private fun familyMessageText(message: FamilyMessage): String = when (message) {
    is FamilyMessage.RequestSent -> when {
        message.action == RemoteAction.RING && message.self ->
            stringResource(if (message.online) R.string.ring_sent_self else R.string.ring_sent_self_offline)
        message.action == RemoteAction.RING ->
            stringResource(if (message.online) R.string.ring_sent else R.string.ring_sent_offline, message.name)
        else -> stringResource(if (message.online) R.string.locate_sent else R.string.locate_sent_offline, message.name)
    }
    is FamilyMessage.NotAllowed -> stringResource(R.string.remote_not_allowed)
    FamilyMessage.PlaceSaved -> stringResource(R.string.place_saved)
    FamilyMessage.PlaceDeleted -> stringResource(R.string.place_deleted)
    FamilyMessage.NoFix -> stringResource(R.string.place_no_fix)
    FamilyMessage.TooManyPlaces -> stringResource(R.string.place_too_many)
    is FamilyMessage.Failed -> uiErrorText(message.error)
}

/** Admins: create an invite link and send it with any app (WhatsApp, SMS, e-mail …). */
@Composable
private fun InviteCard(invite: SharedInvite?, inviting: Boolean, onCreate: () -> Unit, onShareAgain: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.family_invite_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.family_invite_body), style = MaterialTheme.typography.bodyMedium)
            if (invite != null) {
                Text(
                    stringResource(R.string.family_invite_code, invite.code, formatExpiry(invite.expiresAt)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onShareAgain, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.family_invite_share_again))
                }
            }
            Button(onClick = onCreate, enabled = !inviting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (invite == null) R.string.family_invite_create else R.string.family_invite_create_another))
            }
        }
    }
}

private fun formatExpiry(rfc3339: String): String =
    Rfc3339.parseMillis(rfc3339)?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: rfc3339

/** System share sheet with a ready-to-send message; the user picks WhatsApp, SMS, e-mail … */
private fun shareInvite(context: Context, invite: SharedInvite) {
    val text = context.getString(R.string.invite_message, invite.familyName, invite.link, formatExpiry(invite.expiresAt))
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.family_invite_title))
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.family_invite_chooser)))
}

@Composable
private fun MemberCard(
    member: MemberRow,
    now: Long,
    shareBusy: Boolean,
    requestBusy: Boolean,
    myHistoryEnabled: Boolean?,
    onShareChange: (Boolean) -> Unit,
    onHistoryGrantChange: (Boolean) -> Unit,
    onLocate: () -> Unit,
    onRing: () -> Unit,
    onHistory: () -> Unit,
    onOpenMap: (MemberLocation) -> Unit,
) {
    val loc = member.location
    val freshness = loc?.let { Freshness.effective(it.freshness, it.recordedAt, now) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .size(14.dp)
                        .background(parseMemberColor(member.color), CircleShape)
                        .clearAndSetSemantics { },
                )
                Text(
                    if (member.isSelf) stringResource(R.string.family_you, member.name) else member.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (freshness != null) FreshnessBadge(freshness)
            }

            if (loc != null) {
                val recordedAt = Rfc3339.parseMillis(loc.recordedAt)
                if (recordedAt != null) {
                    Text(
                        stringResource(
                            R.string.family_seen,
                            relativeAgeText(recordedAt, now),
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(recordedAt)),
                        ),
                        color = if (freshness == Freshness.STALE) LocalStatusColors.current.warning else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(stringResource(R.string.family_accuracy, loc.accuracyM.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                motionText(loc.motion)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                loc.batteryPct?.let { pct ->
                    Text(
                        if (loc.batteryCharging == true) stringResource(R.string.family_battery_charging, pct) else stringResource(R.string.family_battery, pct),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { onOpenMap(loc) }) { Text(stringResource(R.string.family_open_map)) }
            } else {
                Text(
                    stringResource(
                        when {
                            member.isSelf -> R.string.family_self_no_location
                            !member.sharesWithMe -> R.string.family_not_shared
                            else -> R.string.family_no_location
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Remote actions (the server decides whether they are allowed; 403 is explained).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (member.isSelf) {
                    OutlinedButton(onClick = onRing, enabled = !requestBusy) { Text(stringResource(R.string.member_ring_self)) }
                    if (myHistoryEnabled == true) {
                        OutlinedButton(onClick = onHistory) { Text(stringResource(R.string.member_history_self)) }
                    }
                } else {
                    if (member.sharesWithMe) {
                        OutlinedButton(onClick = onLocate, enabled = !requestBusy) { Text(stringResource(R.string.member_locate)) }
                    }
                    OutlinedButton(onClick = onRing, enabled = !requestBusy) { Text(stringResource(R.string.member_ring)) }
                    if (member.historyVisible) {
                        OutlinedButton(onClick = onHistory) { Text(stringResource(R.string.member_history)) }
                    }
                }
            }

            if (!member.isSelf) {
                SwitchRow(
                    title = stringResource(R.string.family_share_with, member.name),
                    description = null,
                    checked = member.iShareWith,
                    enabled = !shareBusy,
                    onCheckedChange = onShareChange,
                )
                if (member.iShareWith) {
                    val historyOn = myHistoryEnabled == true
                    SwitchRow(
                        title = stringResource(R.string.member_history_grant, member.name),
                        description = stringResource(if (historyOn) R.string.member_history_grant_desc else R.string.member_history_grant_off),
                        checked = member.seesMyHistory && historyOn,
                        enabled = !shareBusy && historyOn,
                        onCheckedChange = onHistoryGrantChange,
                    )
                }
            }
        }
    }
}
