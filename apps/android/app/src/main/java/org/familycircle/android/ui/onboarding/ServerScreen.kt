package org.familycircle.android.ui.onboarding

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import org.familycircle.android.api.ServerUrl
import org.familycircle.android.data.AccountRepository
import org.familycircle.android.ui.components.WarningCard
import org.familycircle.android.ui.components.containerViewModel

enum class ServerError { EMPTY, INVALID, SCHEME, CREDENTIALS, QUERY, NETWORK, REDIRECT, NOT_FAMILYCIRCLE, UNEXPECTED }

data class ServerUiState(
    val input: String = "",
    val busy: Boolean = false,
    val error: ServerError? = null,
    val confirmCleartext: Boolean = false,
    val verified: ServerUrl.Result.Valid? = null,
    val info: InstanceInfo? = null,
)

class ServerViewModel(private val account: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    /** The user confirmed the cleartext warning for exactly this URL. */
    private var cleartextAcceptedFor: String? = null

    fun onInput(value: String) = _state.update { ServerUiState(input = value) }

    fun verify() {
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

    fun dismissCleartext() = _state.update { it.copy(confirmCleartext = false) }

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
        account.saveServer(server, info)
    }
}

/** Step 1: enter and verify the server address. */
@Composable
fun ServerScreen() {
    val vm = containerViewModel { ServerViewModel(it.account) }
    val state by vm.state.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.server_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.server_intro), style = MaterialTheme.typography.bodyLarge)
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
            Button(onClick = vm::verify, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
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
