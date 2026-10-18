package org.familycircle.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.familycircle.android.R
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.InstanceInfo
import org.familycircle.android.api.QrLink
import org.familycircle.android.api.ServerUrl
import org.familycircle.android.data.AccountRepository
import org.familycircle.android.data.OnboardingHandoff
import org.familycircle.android.ui.auth.defaultDeviceName
import org.familycircle.android.ui.components.AppLogo
import org.familycircle.android.ui.components.WarningCard
import org.familycircle.android.ui.components.containerViewModel

enum class ServerError { EMPTY, INVALID, SCHEME, CREDENTIALS, QUERY, NETWORK, REDIRECT, NOT_FAMILYCIRCLE, UNEXPECTED }

/** Why connecting with a pairing code failed. */
enum class PairError { INVALID_CODE, UNSUPPORTED, DEVICE_NAME, RATE_LIMITED, NETWORK, UNEXPECTED }

data class ServerUiState(
    val input: String = "",
    val busy: Boolean = false,
    val error: ServerError? = null,
    val confirmCleartext: Boolean = false,
    val verified: ServerUrl.Result.Valid? = null,
    val info: InstanceInfo? = null,
    /** Scanned pairing/invite link waiting for the verified server (memory only, code redacted in toString). */
    val pending: QrLink? = null,
    val showScanner: Boolean = false,
    val deviceName: String = defaultDeviceName(),
    val pairError: PairError? = null,
)

class ServerViewModel(private val account: AccountRepository, private val handoff: OnboardingHandoff) : ViewModel() {
    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    /** The user confirmed the cleartext warning for exactly this URL. */
    private var cleartextAcceptedFor: String? = null

    fun onInput(value: String) = _state.update { ServerUiState(input = value, deviceName = it.deviceName) }

    fun onDeviceName(value: String) = _state.update { it.copy(deviceName = value.take(64), pairError = null) }

    fun openScanner() = _state.update { it.copy(showScanner = true) }

    fun closeScanner() = _state.update { it.copy(showScanner = false) }

    /** A FamilyCircle QR code was scanned: verify its server, then continue with its purpose. */
    fun onScanned(link: QrLink) {
        _state.update {
            ServerUiState(
                input = link.origin,
                pending = link.takeIf { l -> l !is QrLink.Server },
                deviceName = it.deviceName,
            )
        }
        verify()
    }

    fun verify() {
        // A pasted pairing/invite link works like a scanned one.
        val pasted = QrLink.parse(_state.value.input)
        if (pasted is QrLink.Pair || pasted is QrLink.Register) {
            _state.update { it.copy(input = pasted.origin, pending = pasted) }
        }
        when (val result = ServerUrl.normalize(_state.value.input)) {
            is ServerUrl.Result.Invalid -> _state.update {
                it.copy(
                    error = when (result.reason) {
                        ServerUrl.Reason.EMPTY -> ServerError.EMPTY
                        ServerUrl.Reason.UNSUPPORTED_SCHEME -> ServerError.SCHEME
                        ServerUrl.Reason.CREDENTIALS -> ServerError.CREDENTIALS
                        ServerUrl.Reason.QUERY_OR_FRAGMENT -> ServerError.QUERY
                        ServerUrl.Reason.MALFORMED -> ServerError.INVALID
                    },
                    info = null,
                    verified = null,
                )
            }
            is ServerUrl.Result.Valid ->
                if (result.cleartext && cleartextAcceptedFor != result.url) {
                    _state.update { it.copy(confirmCleartext = true, error = null) }
                } else {
                    check(result)
                }
        }
    }

    fun confirmCleartext() {
        val result = ServerUrl.normalize(_state.value.input) as? ServerUrl.Result.Valid ?: return
        cleartextAcceptedFor = result.url
        _state.update { it.copy(confirmCleartext = false) }
        check(result)
    }

    fun dismissCleartext() = _state.update { it.copy(confirmCleartext = false, pending = null) }

    fun discardPending() = _state.update { it.copy(pending = null, pairError = null) }

    private fun check(server: ServerUrl.Result.Valid) {
        _state.update { it.copy(busy = true, error = null, info = null, verified = null) }
        viewModelScope.launch {
            try {
                val info = account.verifyServer(server)
                _state.update { it.copy(busy = false, info = info, verified = server, input = server.url) }
            } catch (e: ApiException) {
                val error = when {
                    e is ApiException.Network -> ServerError.NETWORK
                    e is ApiException.Server && e.status in 300..399 -> ServerError.REDIRECT
                    e is ApiException.InvalidResponse || (e is ApiException.Server && e.status == 404) ->
                        ServerError.NOT_FAMILYCIRCLE
                    else -> ServerError.UNEXPECTED
                }
                _state.update { it.copy(busy = false, error = error) }
            }
        }
    }

    fun continueWithServer() {
        val s = _state.value
        val server = s.verified ?: return
        val info = s.info ?: return
        if (info.setupRequired) return
        when (val link = s.pending) {
            is QrLink.Pair -> pair(server, info, link)
            is QrLink.Register -> {
                handoff.offerInviteCode(link.code)
                _state.update { it.copy(pending = null) }
                account.saveServer(server, info)
            }
            else -> account.saveServer(server, info)
        }
    }

    private fun pair(server: ServerUrl.Result.Valid, info: InstanceInfo, link: QrLink.Pair) {
        val name = _state.value.deviceName.trim()
        if (name.isEmpty() || name.length > 64) {
            _state.update { it.copy(pairError = PairError.DEVICE_NAME) }
            return
        }
        _state.update { it.copy(busy = true, pairError = null) }
        viewModelScope.launch {
            try {
                account.pair(server, info, link.code, name)
                // Success: the app root switches to the permission walkthrough.
                _state.update { it.copy(busy = false, pending = null) }
            } catch (e: ApiException) {
                val error = when {
                    e is ApiException.Unauthorized -> PairError.INVALID_CODE
                    e is ApiException.Network -> PairError.NETWORK
                    e is ApiException.Server && e.status == 429 -> PairError.RATE_LIMITED
                    e is ApiException.Server && e.status == 400 && "device_name" in e.fields -> PairError.DEVICE_NAME
                    e is ApiException.Server && e.status == 400 -> PairError.UNSUPPORTED
                    else -> PairError.UNEXPECTED
                }
                // An invalid code cannot become valid: forget it, the user scans a new one.
                _state.update {
                    it.copy(busy = false, pairError = error, pending = if (error == PairError.INVALID_CODE) null else it.pending)
                }
            }
        }
    }
}

/** Step 1: scan the QR code from the web app, or enter and verify the server address. */
@Composable
fun ServerScreen() {
    val vm = containerViewModel { ServerViewModel(it.account, it.onboarding) }
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.showScanner) {
        BackHandler(onBack = vm::closeScanner)
        QrScannerScreen(onLink = vm::onScanned, onClose = vm::closeScanner)
        return
    }

    val scroll = rememberScrollState()
    // After a successful verification the next step (pairing card / continue) is at the bottom.
    LaunchedEffect(state.info, state.pending) {
        if (state.info != null) {
            withFrameNanos { } // let the new cards be laid out first
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(scroll)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppLogo()
            Text(stringResource(R.string.server_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.server_intro_qr), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = vm::openScanner, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.server_scan_qr))
            }
            HorizontalDivider()
            Text(stringResource(R.string.server_manual_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.server_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.input,
                onValueChange = vm::onInput,
                label = { Text(stringResource(R.string.server_url_label)) },
                placeholder = { Text("https://familycircle.example.org") },
                supportingText = {
                    val err = state.error
                    if (err != null && err != ServerError.NETWORK && err != ServerError.REDIRECT &&
                        err != ServerError.NOT_FAMILYCIRCLE && err != ServerError.UNEXPECTED
                    ) {
                        Text(serverErrorText(err))
                    } else {
                        Text(stringResource(R.string.server_url_hint))
                    }
                },
                isError = state.error != null,
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { vm.verify() }),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = vm::verify, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.server_verify))
            }
            if (state.busy) CircularProgressIndicator()

            state.error?.takeIf {
                it == ServerError.NETWORK || it == ServerError.REDIRECT || it == ServerError.NOT_FAMILYCIRCLE || it == ServerError.UNEXPECTED
            }?.let { err ->
                Text(
                    serverErrorText(err),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            val info = state.info
            val verified = state.verified
            if (info != null && verified != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.server_connected, info.version),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Text(verified.url, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (verified.cleartext) {
                    WarningCard(stringResource(R.string.cleartext_banner_title), stringResource(R.string.cleartext_banner_body))
                }
                if (info.setupRequired) {
                    WarningCard(stringResource(R.string.server_setup_required_title), stringResource(R.string.server_setup_required_body))
                } else {
                    when (state.pending) {
                        is QrLink.Pair -> PairCard(state, vm)
                        is QrLink.Register -> {
                            Text(stringResource(R.string.server_qr_invite), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = vm::continueWithServer, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.server_continue_register))
                            }
                        }
                        else -> {
                            if (info.registrationMode == "closed") {
                                Text(stringResource(R.string.server_registration_closed), style = MaterialTheme.typography.bodyMedium)
                            }
                            Button(onClick = vm::continueWithServer, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.action_continue))
                            }
                        }
                    }
                }
            }
            state.pairError?.let { err ->
                Text(
                    pairErrorText(err),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (err == PairError.INVALID_CODE) {
                    OutlinedButton(onClick = vm::openScanner, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.server_scan_again))
                    }
                }
            }
        }
    }

    if (state.confirmCleartext) {
        AlertDialog(
            onDismissRequest = vm::dismissCleartext,
            title = { Text(stringResource(R.string.cleartext_dialog_title)) },
            text = { Text(stringResource(R.string.cleartext_dialog_body)) },
            confirmButton = {
                TextButton(onClick = vm::confirmCleartext) { Text(stringResource(R.string.cleartext_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissCleartext) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Confirmation before a pairing code is redeemed: shows the server and lets the user name the phone. */
@Composable
private fun PairCard(state: ServerUiState, vm: ServerViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pair_body, state.verified?.url.orEmpty()), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.deviceName,
                onValueChange = vm::onDeviceName,
                label = { Text(stringResource(R.string.login_device_name)) },
                supportingText = { Text(stringResource(R.string.login_device_name_hint)) },
                singleLine = true,
                isError = state.pairError == PairError.DEVICE_NAME,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = vm::continueWithServer, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pair_connect))
            }
            TextButton(onClick = vm::discardPending, enabled = !state.busy) {
                Text(stringResource(R.string.pair_use_password))
            }
        }
    }
}

@Composable
private fun pairErrorText(error: PairError): String = stringResource(
    when (error) {
        PairError.INVALID_CODE -> R.string.pair_error_invalid
        PairError.UNSUPPORTED -> R.string.pair_error_unsupported
        PairError.DEVICE_NAME -> R.string.login_error_device_name
        PairError.RATE_LIMITED -> R.string.error_rate_limited
        PairError.NETWORK -> R.string.error_network
        PairError.UNEXPECTED -> R.string.error_unexpected
    },
)

@Composable
private fun serverErrorText(error: ServerError): String = stringResource(
    when (error) {
        ServerError.EMPTY -> R.string.server_error_empty
        ServerError.INVALID -> R.string.server_error_invalid
        ServerError.SCHEME -> R.string.server_error_scheme
        ServerError.CREDENTIALS -> R.string.server_error_credentials
        ServerError.QUERY -> R.string.server_error_query
        ServerError.NETWORK -> R.string.server_error_network
        ServerError.REDIRECT -> R.string.server_error_redirect
        ServerError.NOT_FAMILYCIRCLE -> R.string.server_error_not_fc
        ServerError.UNEXPECTED -> R.string.error_unexpected
    },
)
