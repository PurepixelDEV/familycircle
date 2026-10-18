package org.familycircle.android.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.familycircle.android.R
import org.familycircle.android.ui.components.SectionTitle
import org.familycircle.android.ui.theme.LocalStatusColors

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Snapshot of the permissions relevant for sharing. */
data class PermissionSnapshot(
    val precise: Boolean,
    val approximate: Boolean,
    val notifications: Boolean,
    val background: Boolean,
) {
    val location: Boolean get() = precise || approximate

    companion object {
        fun read(context: Context) = PermissionSnapshot(
            precise = context.has(Manifest.permission.ACCESS_FINE_LOCATION),
            approximate = context.has(Manifest.permission.ACCESS_COARSE_LOCATION),
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.has(Manifest.permission.POST_NOTIFICATIONS),
            background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                context.has(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        )
    }
}

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Plain-language permission walkthrough. Precise location is needed for sharing; notifications are
 * recommended (status of sharing); "Allow all the time" is optional and only used to resume after a
 * restart. Every step can be skipped and changed later.
 *
 * @param onBack set when opened from Settings (shows "Back" instead of the onboarding button text).
 */
@Composable
fun PermissionScreen(onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val perms = remember(refreshTick) { PermissionSnapshot.read(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshTick++ }

    var locationDeniedPermanently by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refreshTick++
        val anyGranted = result.values.any { it }
        locationDeniedPermanently = !anyGranted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTick++ }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.perm_intro), style = MaterialTheme.typography.bodyLarge)

            // 1. Location (needed)
            StepCard(
                title = stringResource(R.string.perm_location_title),
                body = stringResource(R.string.perm_location_body),
                status = when {
                    perms.precise -> stringResource(R.string.perm_status_precise)
                    perms.approximate -> stringResource(R.string.perm_status_approximate)
                    else -> null
                },
            ) {
                if (!perms.precise) {
                    if (locationDeniedPermanently) {
                        Text(stringResource(R.string.perm_denied_settings), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.perm_open_settings))
                        }
                    } else {
                        Button(
                            onClick = {
                                locationLauncher.launch(
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.perm_location_button)) }
                    }
                }
            }

            // 2. Notifications (recommended, Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                StepCard(
                    title = stringResource(R.string.perm_notif_title),
                    body = stringResource(R.string.perm_notif_body),
                    status = if (perms.notifications) stringResource(R.string.perm_status_allowed) else null,
                ) {
                    if (!perms.notifications) {
                        OutlinedButton(
                            onClick = { singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.perm_notif_button)) }
                    }
                }
            }

            // 3. Background (optional, Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                StepCard(
                    title = stringResource(R.string.perm_bg_title),
                    body = stringResource(R.string.perm_bg_body),
                    status = if (perms.background) stringResource(R.string.perm_status_allowed) else null,
                ) {
                    if (!perms.background) {
                        if (!perms.location) {
                            Text(stringResource(R.string.perm_bg_needs_location), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                stringResource(R.string.perm_bg_how, backgroundOptionLabel(context)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(
                                onClick = { singleLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.perm_bg_button)) }
                        }
                    }
                }
            }

            Text(stringResource(R.string.perm_change_later), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBack ?: onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (onBack != null) R.string.action_back else R.string.action_continue))
            }
        }
    }
}

/** The system's own wording of "Allow all the time" (Android 11+), so the instructions match the screen. */
private fun backgroundOptionLabel(context: Context): CharSequence =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.backgroundPermissionOptionLabel
    } else {
        context.getString(R.string.perm_bg_label_fallback)
    }

@Composable
private fun StepCard(title: String, body: String, status: String?, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(title)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (status != null) {
                Text("✓ $status", color = LocalStatusColors.current.ok, style = MaterialTheme.typography.labelLarge)
            }
            actions()
        }
    }
}
