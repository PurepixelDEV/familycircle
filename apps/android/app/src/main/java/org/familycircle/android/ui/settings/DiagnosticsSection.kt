package org.familycircle.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.familycircle.android.R
import org.familycircle.android.live.LiveConnectionState
import org.familycircle.android.tracking.BatteryOptimization
import org.familycircle.android.tracking.TrackingRuntime
import org.familycircle.android.ui.components.LocalAppContainer
import org.familycircle.android.ui.components.SectionTitle
import org.familycircle.android.ui.components.relativeAgeText
import org.familycircle.android.ui.components.rememberNow
import org.familycircle.android.ui.components.uploadErrorText
import kotlin.math.roundToInt

/** "Connect another phone": pairing codes are created in the web app. */
@Composable
fun ConnectPhoneCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.settings_connect_phone))
            Text(stringResource(R.string.settings_connect_phone_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Diagnostics for hardware testers. The copied report has no coordinates, tokens, e-mail address
 * or server address.
 */
@Composable
fun DiagnosticsSection() {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val queue by container.dao.countFlow().collectAsStateWithLifecycle(0)
    val running by TrackingRuntime.running.collectAsStateWithLifecycle()
    val effective by TrackingRuntime.effectiveMode.collectAsStateWithLifecycle()
    val providers by TrackingRuntime.providers.collectAsStateWithLifecycle()
    val liveState by container.live.state.collectAsStateWithLifecycle()
    val liveHolders by container.liveHub.holderNames.collectAsStateWithLifecycle()
    val now by rememberNow(15_000)
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var expanded by rememberSaveable { mutableStateOf(false) }

    val snapshot = remember(prefs, queue, running, effective, providers, liveState, liveHolders, tick, now) {
        DiagnosticsSnapshot.collect(context, prefs, queue, running, effective, providers, liveState, container.live.lastConnectedAtMs, liveHolders)
    }
    val copied = stringResource(R.string.diag_copied)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.diag_title))
            Text(stringResource(R.string.diag_intro), style = MaterialTheme.typography.bodyMedium)

            // Battery optimisation (optional, explained in plain language).
            Text(
                stringResource(if (snapshot.ignoringBatteryOptimizations) R.string.diag_battery_exempt else R.string.diag_battery_restricted),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!snapshot.ignoringBatteryOptimizations) {
                Text(stringResource(R.string.diag_battery_explain), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { BatteryOptimization.request(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diag_battery_button))
                }
            }

            val toggleState = stringResource(if (expanded) R.string.diag_state_shown else R.string.diag_state_hidden)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.semantics { stateDescription = toggleState }) {
                Text(stringResource(if (expanded) R.string.diag_hide else R.string.diag_show))
            }
            if (expanded) {
                val yes = stringResource(R.string.diag_yes)
                val no = stringResource(R.string.diag_no)
                fun yn(b: Boolean) = if (b) yes else no
                DiagLine(R.string.diag_app, "${snapshot.appVersion} (${snapshot.versionCode}, ${snapshot.buildType})")
                DiagLine(R.string.diag_android, "${snapshot.androidRelease} (API ${snapshot.sdkInt}) · ${snapshot.manufacturer} ${snapshot.model}")
                DiagLine(R.string.diag_service, yn(snapshot.serviceRunning))
                DiagLine(R.string.diag_providers_in_use, snapshot.providersInUse.joinToString().ifEmpty { "–" })
                DiagLine(R.string.diag_providers_available, snapshot.providersAvailable.joinToString().ifEmpty { "–" })
                DiagLine(R.string.diag_location_enabled, yn(snapshot.locationEnabled))
                DiagLine(R.string.diag_perm_fine, yn(snapshot.precise))
                DiagLine(R.string.diag_perm_coarse, yn(snapshot.approximate))
                DiagLine(R.string.diag_perm_background, yn(snapshot.background))
                DiagLine(R.string.diag_perm_notifications, yn(snapshot.notifications))
                DiagLine(R.string.diag_perm_camera, yn(snapshot.camera))
                DiagLine(R.string.diag_battery_label, yn(snapshot.ignoringBatteryOptimizations))
                DiagLine(R.string.diag_queue, snapshot.queueSize.toString())
                DiagLine(
                    R.string.diag_last_fix,
                    snapshot.lastFixAtMs?.let { at ->
                        relativeAgeText(at, now) + (snapshot.lastFixAccuracyM?.let { " · ±${it.roundToInt()} m" } ?: "")
                    } ?: "–",
                )
                DiagLine(
                    R.string.diag_last_upload,
                    when (snapshot.lastUploadOk) {
                        null -> "–"
                        true -> pluralStringResource(R.plurals.diag_upload_ok, snapshot.lastUploadCount, snapshot.lastUploadAtMs?.let { relativeAgeText(it, now) }.orEmpty(), snapshot.lastUploadCount)
                        false -> snapshot.lastUploadAtMs?.let { relativeAgeText(it, now) }.orEmpty() + " · " +
                            uploadErrorText(snapshot.lastUploadError.orEmpty())
                    },
                )
                DiagLine(
                    R.string.diag_live,
                    stringResource(
                        when (snapshot.liveState) {
                            LiveConnectionState.CONNECTED -> R.string.diag_live_connected
                            LiveConnectionState.CONNECTING, LiveConnectionState.RECONNECTING -> R.string.diag_live_connecting
                            LiveConnectionState.IDLE -> R.string.diag_live_idle
                        },
                    ),
                )
            }
            Button(
                onClick = {
                    copyToClipboard(context, DiagnosticsReport.format(snapshot))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.diag_copy)) }
            Text(stringResource(R.string.diag_copy_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DiagLine(label: Int, value: String) {
    Text("${stringResource(label)}: $value", style = MaterialTheme.typography.bodyMedium)
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("FamilyCircle diagnostics", text))
}
