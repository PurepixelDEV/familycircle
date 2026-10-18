package org.familycircle.android.ui.home

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.familycircle.android.R
import org.familycircle.android.data.prefs.TrackingProblem
import org.familycircle.android.tracking.BatteryOptimization
import org.familycircle.android.tracking.Notifications
import org.familycircle.android.tracking.TrackingMode
import org.familycircle.android.tracking.TrackingPolicy
import org.familycircle.android.ui.components.SectionTitle
import org.familycircle.android.ui.components.WarningCard
import org.familycircle.android.ui.components.containerViewModel
import org.familycircle.android.ui.components.problemText
import org.familycircle.android.ui.components.relativeAgeText
import org.familycircle.android.ui.components.rememberNow
import org.familycircle.android.ui.components.uiErrorText
import org.familycircle.android.ui.components.uploadErrorText
import org.familycircle.android.ui.permissions.PermissionSnapshot
import org.familycircle.android.ui.theme.LocalStatusColors
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(snackbar: SnackbarHostState) {
    val vm = containerViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val now by rememberNow()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.start()
    }
    val requestPermissionThenStart = {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val start = { if (vm.hasLocationPermission()) vm.start() else requestPermissionThenStart() }

    val messageText = message?.let { homeMessageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            vm.messageShown()
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall)

        if (state.prefs.serverCleartext) {
            WarningCard(stringResource(R.string.cleartext_banner_title), stringResource(R.string.cleartext_banner_body))
        }
        if (state.sharingPaused == true) {
            WarningCard(stringResource(R.string.home_sharing_paused_title), stringResource(R.string.home_sharing_paused_body)) {
                TextButton(onClick = vm::resumeSharingOnServer) { Text(stringResource(R.string.home_sharing_paused_resume)) }
            }
        }

        StatusCard(state, now)

        state.prefs.trackingProblem?.let { problem ->
            WarningCard(stringResource(R.string.home_problem_title), problemText(problem)) {
                when (problem) {
                    TrackingProblem.PERMISSION_MISSING ->
                        TextButton(onClick = requestPermissionThenStart) { Text(stringResource(R.string.home_problem_grant)) }
                    TrackingProblem.LOCATION_DISABLED ->
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }) { Text(stringResource(R.string.home_problem_location_settings)) }
                    else -> Unit
                }
            }
        }

        if (state.status == TrackingStatus.ON) BackgroundCard()

        when (state.status) {
            TrackingStatus.ON -> OutlinedButton(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_stop))
            }
            TrackingStatus.PAUSED -> Button(onClick = start, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_resume))
            }
            TrackingStatus.OFF -> Button(onClick = start, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_start))
            }
        }
        if (state.status == TrackingStatus.PAUSED) {
            TextButton(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_turn_off)) }
        }

        OutlinedButton(
            onClick = vm::sendNow,
            enabled = !state.sendingNow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.sendingNow) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  " + stringResource(R.string.home_send_now_running))
            } else {
                Text(stringResource(R.string.home_send_now))
            }
        }

        ModeSelector(
            selected = if (state.status == TrackingStatus.OFF) TrackingMode.OFF else state.prefs.trackingMode,
            onSelect = { mode ->
                when {
                    mode == TrackingMode.OFF -> vm.stop()
                    state.status == TrackingStatus.ON -> vm.selectMode(mode)
                    else -> {
                        // Picking a mode while off/paused means "share in this mode".
                        vm.selectMode(mode)
                        start()
                    }
                }
            },
        )
    }
}

/**
 * While sharing is on: offers what Android needs so that swiping the app away, a reboot or an app
 * update does not end sharing (see [org.familycircle.android.tracking.BackgroundResume]).
 * Hidden once both are granted.
 */
@Composable
private fun BackgroundCard() {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshTick++ }
    val batteryExempt = remember(refreshTick) { BatteryOptimization.isExempt(context) }
    val background = remember(refreshTick) { PermissionSnapshot.read(context).background }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTick++ }
    if (batteryExempt && background) return

    WarningCard(stringResource(R.string.home_background_title), stringResource(R.string.home_background_body)) {
        if (!batteryExempt) {
            TextButton(onClick = { BatteryOptimization.request(context) }) { Text(stringResource(R.string.home_background_battery)) }
        }
        if (!background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TextButton(onClick = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                Text(stringResource(R.string.home_background_always))
            }
        }
    }
}

@Composable
private fun StatusCard(state: HomeUiState, now: Long) {
    val status = LocalStatusColors.current
    val (label, dotColor) = when (state.status) {
        TrackingStatus.ON -> stringResource(R.string.status_on) to status.ok
        TrackingStatus.PAUSED -> stringResource(R.string.status_paused) to status.warning
        TrackingStatus.OFF -> stringResource(R.string.status_off) to status.neutral
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Dot(dotColor, 16)
                Text(
                    label,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val selected = state.prefs.trackingMode
            if (state.status != TrackingStatus.OFF) {
                Text(stringResource(R.string.status_mode, stringResource(Notifications.modeLabelRes(selected))))
                val effective = state.effectiveMode
                if (state.status == TrackingStatus.ON && effective != null && effective != selected) {
                    Text(
                        stringResource(R.string.status_mode_reduced, stringResource(Notifications.modeLabelRes(effective))),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.status == TrackingStatus.ON && selected == TrackingMode.LIVE) {
                    val remainingMin = (TrackingPolicy().liveRemainingMs(state.prefs.liveStartedAtMs, now) / 60_000).toInt()
                    if (remainingMin > 0) {
                        Text(pluralStringResource(R.plurals.status_live_remaining, remainingMin, remainingMin), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            HorizontalDivider()
            val lastFix = state.prefs.lastFixAtMs
            Text(
                if (lastFix != null) {
                    stringResource(
                        R.string.status_last_fix,
                        relativeAgeText(lastFix, now),
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastFix)),
                    )
                } else {
                    stringResource(R.string.status_no_fix)
                },
            )
            Text(
                if (state.queueSize == 0) {
                    stringResource(R.string.status_queue_empty)
                } else {
                    pluralStringResource(R.plurals.status_queue, state.queueSize, state.queueSize)
                },
            )
            val uploadAt = state.prefs.lastUploadAtMs
            if (uploadAt != null) {
                val age = relativeAgeText(uploadAt, now)
                val error = state.prefs.lastUploadError
                if (state.prefs.lastUploadOk == true || error == null) {
                    Text(pluralStringResource(R.plurals.status_upload_ok, state.prefs.lastUploadCount, age, state.prefs.lastUploadCount))
                } else {
                    Text(
                        stringResource(R.string.status_upload_failed, age, uploadErrorText(error)),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(selected: TrackingMode, onSelect: (TrackingMode) -> Unit) {
    Column(Modifier.selectableGroup()) {
        SectionTitle(stringResource(R.string.mode_title))
        Text(stringResource(R.string.mode_intro), style = MaterialTheme.typography.bodyMedium)
        val modes = listOf(
            Triple(TrackingMode.SAVER, R.string.mode_saver, R.string.mode_saver_desc),
            Triple(TrackingMode.BALANCED, R.string.mode_balanced, R.string.mode_balanced_desc),
            Triple(TrackingMode.LIVE, R.string.mode_live, R.string.mode_live_desc),
            Triple(TrackingMode.OFF, R.string.mode_off, R.string.mode_off_desc),
        )
        for ((mode, title, desc) in modes) {
            val isSelected = selected == mode
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(mode) })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Dot(color: Color, sizeDp: Int) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun homeMessageText(message: HomeMessage): String = when (message) {
    is HomeMessage.Sent -> stringResource(R.string.home_msg_sent)
    HomeMessage.Queued -> stringResource(R.string.home_msg_queued)
    HomeMessage.NoFix -> stringResource(R.string.home_msg_no_fix)
    is HomeMessage.Problem -> problemText(message.problem)
    is HomeMessage.Error -> uiErrorText(message.error)
}
