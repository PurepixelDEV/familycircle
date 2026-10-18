package org.familycircle.android.ui.family

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.familycircle.android.R
import org.familycircle.android.api.MemberLocation
import org.familycircle.android.ui.components.SwitchRow
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
fun FamilyScreen(snackbar: SnackbarHostState) {
    val vm = containerViewModel { FamilyViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now by rememberNow(10_000)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val noMapsApp = stringResource(R.string.family_no_maps_app)

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
        items(state.members, key = { it.userId }) { member ->
            MemberCard(
                member = member,
                now = now,
                shareBusy = member.userId in state.shareBusy,
                onShareChange = { vm.setSharing(member.userId, it) },
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
    }
}

@Composable
private fun MemberCard(
    member: MemberRow,
    now: Long,
    shareBusy: Boolean,
    onShareChange: (Boolean) -> Unit,
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
                        .background(parseColor(member.color), CircleShape)
                        .clearAndSetSemantics { },
                )
                Text(
                    if (member.isSelf) stringResource(R.string.family_you, member.name) else member.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (freshness != null) FreshnessChip(freshness)
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

            if (!member.isSelf) {
                SwitchRow(
                    title = stringResource(R.string.family_share_with, member.name),
                    description = null,
                    checked = member.iShareWith,
                    enabled = !shareBusy,
                    onCheckedChange = onShareChange,
                )
            }
        }
    }
}

@Composable
private fun FreshnessChip(freshness: Freshness) {
    val status = LocalStatusColors.current
    val (text, bg, fg) = when (freshness) {
        Freshness.LIVE -> Triple(stringResource(R.string.freshness_live), status.ok, status.onOk)
        Freshness.RECENT -> Triple(stringResource(R.string.freshness_recent), status.neutral, status.onNeutral)
        Freshness.STALE -> Triple(stringResource(R.string.freshness_stale), status.warning, status.onWarning)
    }
    val description = stringResource(R.string.freshness_cd, text)
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun motionText(motion: String): String? = when (motion) {
    "stationary" -> stringResource(R.string.motion_stationary)
    "walking" -> stringResource(R.string.motion_walking)
    "driving" -> stringResource(R.string.motion_driving)
    else -> null
}

private fun parseColor(hex: String): Color =
    try {
        Color(hex.toColorInt())
    } catch (_: IllegalArgumentException) {
        Color.Gray
    }
