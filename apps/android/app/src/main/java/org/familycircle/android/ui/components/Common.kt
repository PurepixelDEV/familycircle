package org.familycircle.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import org.familycircle.android.AppContainer
import org.familycircle.android.R
import org.familycircle.android.api.ApiException
import org.familycircle.android.data.prefs.TrackingProblem
import org.familycircle.android.ui.theme.LocalStatusColors
import org.familycircle.android.util.RelativeAge

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** ViewModel created from the [AppContainer] (manual DI). */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}

/** Current time, re-emitted every [periodMs] so relative ages and freshness stay correct on screen. */
@Composable
fun rememberNow(periodMs: Long = 15_000): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(periodMs)
        value = System.currentTimeMillis()
    }
}

@Composable
fun relativeAgeText(thenMs: Long, nowMs: Long): String = when (val age = RelativeAge.of(thenMs, nowMs)) {
    RelativeAge.JustNow -> stringResource(R.string.age_just_now)
    is RelativeAge.Minutes -> pluralStringResource(R.plurals.age_minutes, age.value, age.value)
    is RelativeAge.Hours -> pluralStringResource(R.plurals.age_hours, age.value, age.value)
    is RelativeAge.Days -> pluralStringResource(R.plurals.age_days, age.value, age.value)
}

/** User-facing error categories for API failures. */
enum class UiError { NETWORK, SESSION_EXPIRED, RATE_LIMITED, NOT_FOUND, FORBIDDEN, SERVER, UNEXPECTED }

fun Throwable.toUiError(): UiError = when (this) {
    is ApiException.Network -> UiError.NETWORK
    is ApiException.SessionExpired, is ApiException.Unauthorized, is ApiException.NotLoggedIn -> UiError.SESSION_EXPIRED
    is ApiException.Server -> when {
        status == 429 -> UiError.RATE_LIMITED
        status == 404 -> UiError.NOT_FOUND
        status == 403 -> UiError.FORBIDDEN
        status >= 500 -> UiError.SERVER
        else -> UiError.UNEXPECTED
    }
    else -> UiError.UNEXPECTED
}

@Composable
fun uiErrorText(error: UiError): String = stringResource(
    when (error) {
        UiError.NETWORK -> R.string.error_network
        UiError.SESSION_EXPIRED -> R.string.error_session_expired
        UiError.RATE_LIMITED -> R.string.error_rate_limited
        UiError.NOT_FOUND -> R.string.error_not_found
        UiError.FORBIDDEN -> R.string.error_forbidden
        UiError.SERVER -> R.string.error_server
        UiError.UNEXPECTED -> R.string.error_unexpected
    },
)

@Composable
fun problemText(problem: TrackingProblem): String = stringResource(
    when (problem) {
        TrackingProblem.PERMISSION_MISSING -> R.string.problem_permission
        TrackingProblem.LOCATION_DISABLED -> R.string.problem_location_off
        TrackingProblem.NO_PROVIDER -> R.string.problem_no_provider
        TrackingProblem.START_NOT_ALLOWED -> R.string.problem_start_not_allowed
        TrackingProblem.SIGNED_OUT -> R.string.problem_signed_out
    },
)

@Composable
fun uploadErrorText(key: String): String = stringResource(
    when (key) {
        "network" -> R.string.upload_error_network
        "server_unavailable" -> R.string.upload_error_server
        "rate_limited" -> R.string.upload_error_rate_limited
        "unauthorized" -> R.string.upload_error_unauthorized
        "not_configured" -> R.string.upload_error_unauthorized
        else -> R.string.upload_error_rejected
    },
)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() },
    )
}

/** Amber notice box; the text carries the meaning, the colour only supports it. */
@Composable
fun WarningCard(title: String, body: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val status = LocalStatusColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = status.warningContainer,
            contentColor = status.onWarningContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
            if (action != null) action()
        }
    }
}

/** A whole-row toggle (large touch target, announced as a switch by TalkBack). */
@Composable
fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
