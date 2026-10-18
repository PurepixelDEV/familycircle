package org.familycircle.android.ui.auth

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import org.familycircle.android.data.AccountRepository
import org.familycircle.android.ui.components.LocalAppContainer
import org.familycircle.android.ui.components.WarningCard
import org.familycircle.android.ui.components.containerViewModel

enum class LoginError {
    MISSING_FIELDS, PASSWORD_TOO_SHORT, DEVICE_NAME, WRONG_CREDENTIALS, INVITE_INVALID, EMAIL_TAKEN,
    REGISTRATION_CLOSED, RATE_LIMITED, INVALID_INPUT, NETWORK, UNEXPECTED,
}

data class LoginUiState(
    val register: Boolean = false,
    val email: String = "",
    val password: String = "",
    val inviteCode: String = "",
    val displayName: String = "",
    val deviceName: String = defaultDeviceName(),
    val busy: Boolean = false,
    val error: LoginError? = null,
    /** Field names the server flagged (`error.fields`), e.g. "email", "password". */
    val invalidFields: Set<String> = emptySet(),
)

/** `Build.MANUFACTURER + " " + Build.MODEL`, trimmed to the API's 64-character limit. */
fun defaultDeviceName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    val model = Build.MODEL.orEmpty()
    val name = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    return name.trim().ifEmpty { "Android" }.take(64)
}

class LoginViewModel(private val account: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** Invite code from a scanned `/register?code=` QR code (memory only): opens the registration form. */
    fun applyScannedInvite(code: String) =
        _state.update { it.copy(register = true, inviteCode = code, error = null, invalidFields = emptySet()) }

    fun update(transform: (LoginUiState) -> LoginUiState) = _state.update { transform(it).copy(error = null, invalidFields = emptySet()) }

    fun submit() {
        val s = _state.value
        val localError = when {
            s.email.isBlank() || s.password.isEmpty() -> LoginError.MISSING_FIELDS
            s.register && (s.inviteCode.isBlank() || s.displayName.isBlank()) -> LoginError.MISSING_FIELDS
            s.register && s.password.length < 10 -> LoginError.PASSWORD_TOO_SHORT
            s.deviceName.isBlank() || s.deviceName.trim().length > 64 -> LoginError.DEVICE_NAME
            else -> null
        }
        if (localError != null) {
            _state.update { it.copy(error = localError) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                if (s.register) {
                    account.register(s.inviteCode, s.displayName, s.email, s.password, s.deviceName)
                } else {
                    account.login(s.email, s.password, s.deviceName)
                }
                // Success: the auth state flips to LoggedIn and the app root switches screens.
                _state.update { it.copy(busy = false, password = "") }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = e.toLoginError(), invalidFields = (e as? ApiException.Server)?.fields?.keys.orEmpty()) }
            }
        }
    }

    fun changeServer() {
        viewModelScope.launch { account.changeServer() }
    }

    private fun ApiException.toLoginError(): LoginError = when (this) {
        is ApiException.Network -> LoginError.NETWORK
        is ApiException.Unauthorized -> LoginError.WRONG_CREDENTIALS
        is ApiException.Server -> when {
            code == "invite_invalid" -> LoginError.INVITE_INVALID
            code == "email_taken" -> LoginError.EMAIL_TAKEN
            code == "registration_closed" -> LoginError.REGISTRATION_CLOSED
            status == 429 -> LoginError.RATE_LIMITED
            status == 400 -> LoginError.INVALID_INPUT
            else -> LoginError.UNEXPECTED
        }
        else -> LoginError.UNEXPECTED
    }
}

/** Step 2: sign in, or register with an invite code. */
@Composable
fun LoginScreen(serverUrl: String, sessionExpired: Boolean) {
    val container = LocalAppContainer.current
    val vm = containerViewModel { LoginViewModel(it.account) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        container.onboarding.takeInviteCode()?.let(vm::applyScannedInvite)
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.login_server, serverUrl), style = MaterialTheme.typography.bodyMedium)
            if (sessionExpired) {
                WarningCard(stringResource(R.string.login_session_expired_title), stringResource(R.string.login_session_expired_body))
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !state.register,
                    onClick = { vm.update { it.copy(register = false) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.login_tab_signin)) }
                SegmentedButton(
                    selected = state.register,
                    onClick = { vm.update { it.copy(register = true) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.login_tab_invite)) }
            }

            if (state.register) {
                Text(stringResource(R.string.login_invite_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = state.inviteCode,
                    onValueChange = { v -> vm.update { it.copy(inviteCode = v) } },
                    label = { Text(stringResource(R.string.login_invite_code)) },
                    singleLine = true,
                    isError = "invite_code" in state.invalidFields || state.error == LoginError.INVITE_INVALID,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = { v -> vm.update { it.copy(displayName = v) } },
                    label = { Text(stringResource(R.string.login_display_name)) },
                    supportingText = { Text(stringResource(R.string.login_display_name_hint)) },
                    singleLine = true,
                    isError = "display_name" in state.invalidFields,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = state.email,
                onValueChange = { v -> vm.update { it.copy(email = v) } },
                label = { Text(stringResource(R.string.login_email)) },
                singleLine = true,
                isError = "email" in state.invalidFields,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            // Show/hide toggle instead of a "repeat password" field (NIST SP 800-63B).
            var passwordVisible by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = state.password,
                onValueChange = { v -> vm.update { it.copy(password = v) } },
                label = { Text(stringResource(R.string.login_password)) },
                supportingText = if (state.register) {
                    { Text(stringResource(R.string.login_password_hint)) }
                } else {
                    null
                },
                singleLine = true,
                isError = "password" in state.invalidFields || state.error == LoginError.PASSWORD_TOO_SHORT,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(stringResource(if (passwordVisible) R.string.login_hide_password else R.string.login_show_password))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.deviceName,
                onValueChange = { v -> vm.update { it.copy(deviceName = v.take(64)) } },
                label = { Text(stringResource(R.string.login_device_name)) },
                supportingText = { Text(stringResource(R.string.login_device_name_hint)) },
                singleLine = true,
                isError = "device_name" in state.invalidFields || state.error == LoginError.DEVICE_NAME,
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let { err ->
                Text(
                    loginErrorText(err),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Button(onClick = vm::submit, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.register) R.string.login_submit_register else R.string.login_submit_signin))
            }
            if (state.busy) CircularProgressIndicator()

            TextButton(onClick = vm::changeServer, enabled = !state.busy) {
                Text(stringResource(R.string.login_change_server))
            }
        }
    }
}

@Composable
private fun loginErrorText(error: LoginError): String = stringResource(
    when (error) {
        LoginError.MISSING_FIELDS -> R.string.login_error_missing
        LoginError.PASSWORD_TOO_SHORT -> R.string.login_error_password_short
        LoginError.DEVICE_NAME -> R.string.login_error_device_name
        LoginError.WRONG_CREDENTIALS -> R.string.login_error_credentials
        LoginError.INVITE_INVALID -> R.string.login_error_invite
        LoginError.EMAIL_TAKEN -> R.string.login_error_email_taken
        LoginError.REGISTRATION_CLOSED -> R.string.login_error_registration_closed
        LoginError.RATE_LIMITED -> R.string.error_rate_limited
        LoginError.INVALID_INPUT -> R.string.login_error_invalid_input
        LoginError.NETWORK -> R.string.error_network
        LoginError.UNEXPECTED -> R.string.error_unexpected
    },
)
