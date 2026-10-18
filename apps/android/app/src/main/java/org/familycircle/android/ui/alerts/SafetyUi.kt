package org.familycircle.android.ui.alerts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.familycircle.android.R
import org.familycircle.android.api.Alert
import org.familycircle.android.ui.components.relativeAgeText
import org.familycircle.android.ui.components.uiErrorText
import org.familycircle.android.util.Rfc3339
import kotlin.math.ceil

/** How long the SOS button has to be held. */
const val SOS_HOLD_MS = 3_000

/**
 * SOS button: must be **held for 3 seconds** (circular countdown, haptic tick every second) – a
 * tap does nothing, which prevents false alarms. Screen-reader users get the action "Send SOS"
 * with a confirmation dialog instead of the hold gesture.
 */
@Composable
fun SosHoldButton(
    onTriggered: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    enabled: Boolean = true,
    sending: Boolean = false,
    showHint: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val trigger by rememberUpdatedState(onTriggered)
    val active = enabled && !sending
    val red = colorResource(R.color.sos_red)
    val cd = stringResource(R.string.sos_button_cd)
    val actionLabel = stringResource(R.string.sos_button_action)
    val remaining = ceil((1f - progress.value) * SOS_HOLD_MS / 1000f).toInt().coerceAtLeast(1)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(size)
                .semantics {
                    contentDescription = cd
                    role = Role.Button
                    if (active) {
                        onClick(label = actionLabel) {
                            confirm = true
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .pointerInput(active) {
                    if (!active) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        holding = true
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        val job = scope.launch {
                            launch {
                                repeat(SOS_HOLD_MS / 1000 - 1) {
                                    delay(1000)
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                            }
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(SOS_HOLD_MS, easing = LinearEasing))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            holding = false
                            trigger()
                            progress.snapTo(0f)
                        }
                        waitForUpOrCancellation()
                        if (job.isActive) {
                            job.cancel()
                            holding = false
                            scope.launch { progress.animateTo(0f, tween(200)) }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = this.size.minDimension * 0.07f
                drawCircle(if (active) red else Color(0xFF9E9E9E))
                val inset = stroke * 1.6f
                val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
                drawArc(Color.White.copy(alpha = 0.35f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (progress.value > 0f) {
                    drawArc(Color.White, -90f, 360f * progress.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            if (sending) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(size / 3))
            } else {
                Text(
                    if (holding) remaining.toString() else stringResource(R.string.sos_label),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    // Sized from the circle (dp), not the font scale: the label must fit the button.
                    fontSize = with(LocalDensity.current) { (size * if (holding) 0.36f else 0.26f).toSp() },
                    maxLines = 1,
                )
            }
        }
        if (showHint) {
            Text(
                stringResource(
                    when {
                        sending -> R.string.sos_sending
                        holding -> R.string.sos_keep_holding
                        else -> R.string.sos_hold_hint
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.sos_confirm_title)) },
            text = { Text(stringResource(R.string.sos_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    trigger()
                }) { Text(stringResource(R.string.sos_button_action)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Own active SOS: red banner with "I am safe". */
@Composable
fun OwnSosBanner(resolving: Boolean, onResolve: () -> Unit, modifier: Modifier = Modifier) {
    val red = colorResource(R.color.sos_red)
    Surface(color = red, contentColor = Color.White, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.sos_active_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.sos_active_body), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onResolve,
                enabled = !resolving,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = red),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.sos_resolve), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Other members' active SOS: one red row per person; tapping shows the position. */
@Composable
fun OthersSosBanner(alerts: List<Alert>, now: Long, onShow: (Alert) -> Unit, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) return
    val red = colorResource(R.color.sos_red)
    val showLabel = stringResource(R.string.sos_show_on_map)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        alerts.forEach { alert ->
            val age = Rfc3339.parseMillis(alert.createdAt)?.let { relativeAgeText(it, now) }
            Surface(
                color = red,
                contentColor = Color.White,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClickLabel = showLabel) { onShow(alert) }
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sos_other_title, alert.actor.displayName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(age, if (alert.location == null) stringResource(R.string.sos_no_position) else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (alert.location != null) Text(showLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** "Bin angekommen" with an optional message (≤ 140 characters). */
@Composable
fun CheckInDialog(sending: Boolean, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.checkin_body))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(SafetyViewModel.MAX_MESSAGE) },
                    label = { Text(stringResource(R.string.checkin_message)) },
                    supportingText = { Text("${text.length} / ${SafetyViewModel.MAX_MESSAGE}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(text) }, enabled = !sending) { Text(stringResource(R.string.checkin_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun safetyMessageText(message: SafetyMessage): String = when (message) {
    is SafetyMessage.SosSent -> pluralStringResource(R.plurals.sos_sent, message.recipients, message.recipients)
    SafetyMessage.Resolved -> stringResource(R.string.sos_resolved_msg)
    is SafetyMessage.CheckedIn -> pluralStringResource(R.plurals.checkin_sent, message.recipients, message.recipients)
    SafetyMessage.CheckInNoFix -> stringResource(R.string.checkin_no_fix)
    is SafetyMessage.Failed -> uiErrorText(message.error)
}
