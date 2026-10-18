package org.familycircle.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.familycircle.android.AppContainer
import org.familycircle.android.BuildConfig
import org.familycircle.android.R
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.PrivacySettings
import org.familycircle.android.api.PrivacySettingsUpdate
import org.familycircle.android.data.prefs.AppPrefs
import org.familycircle.android.ui.components.SectionTitle
import org.familycircle.android.ui.components.SwitchRow
import org.familycircle.android.ui.components.UiError
import org.familycircle.android.ui.components.WarningCard
import org.familycircle.android.ui.components.containerViewModel
import org.familycircle.android.ui.components.toUiError
import org.familycircle.android.ui.components.uiErrorText

data class SettingsUiState(
    val prefs: AppPrefs = AppPrefs(),
    val queueSize: Int = 0,
    val settings: PrivacySettings? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: UiError? = null,
    val signingOut: Boolean = false,
)

private data class Remote(val settings: PrivacySettings?, val loading: Boolean, val saving: Boolean, val error: UiError?, val signingOut: Boolean)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val remote = MutableStateFlow(Remote(null, false, false, null, false))

    val state: StateFlow<SettingsUiState> = combine(container.prefs.state, container.dao.countFlow(), remote) { prefs, queue, r ->
        SettingsUiState(prefs, queue, r.settings, r.loading, r.saving, r.error, r.signingOut)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        load()
    }

    fun load() {
        remote.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val settings = container.api.settings()
                container.prefs.setShareBattery(settings.shareBattery)
                remote.update { it.copy(settings = settings, loading = false, error = null) }
            } catch (e: ApiException) {
                remote.update { it.copy(loading = false, error = e.toUiError()) }
            }
        }
    }

    fun update(update: PrivacySettingsUpdate) {
        remote.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val settings = container.api.updateSettings(update)
                container.prefs.setShareBattery(settings.shareBattery)
                remote.update { it.copy(settings = settings, saving = false, error = null) }
            } catch (e: ApiException) {
                remote.update { it.copy(saving = false, error = e.toUiError()) }
            }
        }
    }

    fun logout() {
        remote.update { it.copy(signingOut = true) }
        viewModelScope.launch { container.account.logout() }
    }

    fun changeServer() {
        remote.update { it.copy(signingOut = true) }
        viewModelScope.launch { container.account.changeServer() }
    }
}

private enum class Confirm { LOGOUT, CHANGE_SERVER, DISABLE_HISTORY }

@Composable
fun SettingsScreen(onOpenPermissions: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val settings = state.settings
    val editable = settings != null && !state.saving && !state.signingOut

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        if (state.loading || state.saving || state.signingOut) LinearProgressIndicator(Modifier.fillMaxWidth())

        // Account
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(stringResource(R.string.settings_account))
                state.prefs.userName?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                state.prefs.userEmail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.settings_server, state.prefs.serverUrl.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                state.prefs.serverVersion?.let { Text(stringResource(R.string.settings_server_version, it), style = MaterialTheme.typography.bodyMedium) }
                state.prefs.deviceName?.let { Text(stringResource(R.string.settings_device, it), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (state.prefs.serverCleartext) {
            WarningCard(stringResource(R.string.cleartext_banner_title), stringResource(R.string.cleartext_banner_body))
        }

        // Privacy (server-side settings)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SectionTitle(stringResource(R.string.settings_privacy), Modifier.padding(top = 8.dp))
                state.error?.let { Text(uiErrorText(it), color = MaterialTheme.colorScheme.error) }
                SwitchRow(
                    title = stringResource(R.string.settings_pause_sharing),
                    description = stringResource(R.string.settings_pause_sharing_desc),
                    checked = settings?.sharingPaused == true,
                    enabled = editable,
                    onCheckedChange = { vm.update(PrivacySettingsUpdate(sharingPaused = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_share_battery),
                    description = stringResource(R.string.settings_share_battery_desc),
                    checked = settings?.shareBattery == true,
                    enabled = editable,
                    onCheckedChange = { vm.update(PrivacySettingsUpdate(shareBattery = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_history),
                    description = settings?.let {
                        pluralStringResource(R.plurals.settings_history_desc, it.historyRetentionDays, it.historyRetentionDays)
                    },
                    checked = settings?.historyEnabled == true,
                    enabled = editable,
                    onCheckedChange = { enable ->
                        if (enable) vm.update(PrivacySettingsUpdate(historyEnabled = true)) else confirm = Confirm.DISABLE_HISTORY
                    },
                )
            }
        }

        ConnectPhoneCard()

        OutlinedButton(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_permissions))
        }
        OutlinedButton(onClick = { confirm = Confirm.CHANGE_SERVER }, enabled = !state.signingOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_change_server))
        }
        OutlinedButton(onClick = { confirm = Confirm.LOGOUT }, enabled = !state.signingOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_logout))
        }
        DiagnosticsSection()
        Text(
            stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when (confirm) {
        Confirm.LOGOUT, Confirm.CHANGE_SERVER -> {
            val isLogout = confirm == Confirm.LOGOUT
            ConfirmDialog(
                title = stringResource(if (isLogout) R.string.settings_logout_confirm_title else R.string.settings_change_server_confirm_title),
                body = buildString {
                    append(stringResource(if (isLogout) R.string.settings_logout_confirm_body else R.string.settings_change_server_confirm_body))
                    if (state.queueSize > 0) {
                        append("\n\n")
                        append(pluralStringResource(R.plurals.settings_unsent_lost, state.queueSize, state.queueSize))
                    }
                },
                confirmText = stringResource(if (isLogout) R.string.settings_logout else R.string.settings_change_server),
                onConfirm = {
                    confirm = null
                    if (isLogout) vm.logout() else vm.changeServer()
                },
                onDismiss = { confirm = null },
            )
        }
        Confirm.DISABLE_HISTORY -> ConfirmDialog(
            title = stringResource(R.string.settings_history_off_title),
            body = stringResource(R.string.settings_history_off_body),
            confirmText = stringResource(R.string.settings_history_off_confirm),
            onConfirm = {
                confirm = null
                vm.update(PrivacySettingsUpdate(historyEnabled = false))
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
