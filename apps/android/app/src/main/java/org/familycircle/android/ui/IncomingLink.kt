package org.familycircle.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.familycircle.android.R
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.LinkRoute
import org.familycircle.android.api.LinkRouting
import org.familycircle.android.api.QrLink
import org.familycircle.android.ui.components.LocalAppContainer
import org.familycircle.android.ui.components.toUiError
import org.familycircle.android.ui.components.uiErrorRes

/**
 * Links opened from outside while signed in: an invite of this server asks whether to join the
 * family (`POST /invites/accept`); everything else only explains – a link never signs the user
 * out or switches servers.
 */
@Composable
fun IncomingLinkInApp(snackbar: SnackbarHostState) {
    val container = LocalAppContainer.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val incoming by container.onboarding.link.collectAsStateWithLifecycle()
    var invite by remember { mutableStateOf<QrLink.Register?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(incoming) {
        val link = incoming ?: return@LaunchedEffect
        // Taking the link changes `incoming` and restarts this effect: anything that suspends
        // (the snackbar) must run in the screen's scope, not here.
        if (!container.onboarding.takeLink(link)) return@LaunchedEffect
        when (LinkRouting.route(link, container.prefs.current.serverUrl, loggedIn = true)) {
            LinkRoute.ASK_JOIN -> invite = link as QrLink.Register
            LinkRoute.OTHER_SERVER ->
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.link_other_server, link.origin)) }
            else -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.link_already_connected)) }
        }
    }

    val pending = invite ?: return
    AlertDialog(
        onDismissRequest = { if (!busy) invite = null },
        title = { Text(stringResource(R.string.link_join_title)) },
        text = { Text(stringResource(R.string.link_join_body)) },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val message = try {
                            val family = container.api.acceptInvite(pending.code)
                            container.prefs.setSelectedFamily(family.id)
                            resources.getString(R.string.link_joined, family.name)
                        } catch (e: ApiException) {
                            resources.getString(
                                when {
                                    e is ApiException.Server && e.status == 409 -> R.string.link_already_member
                                    e is ApiException.Server && e.status == 400 -> R.string.link_invite_invalid
                                    else -> uiErrorRes(e.toUiError())
                                },
                            )
                        }
                        busy = false
                        invite = null
                        snackbar.showSnackbar(message)
                    }
                },
            ) { Text(stringResource(R.string.link_join_confirm)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { invite = null }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
