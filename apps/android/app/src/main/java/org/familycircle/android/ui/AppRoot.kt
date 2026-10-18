package org.familycircle.android.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import org.familycircle.android.R
import org.familycircle.android.api.AuthSession
import org.familycircle.android.ui.auth.LoginScreen
import org.familycircle.android.ui.components.LocalAppContainer
import org.familycircle.android.ui.family.FamilyScreen
import org.familycircle.android.ui.home.HomeScreen
import org.familycircle.android.ui.onboarding.ServerScreen
import org.familycircle.android.ui.permissions.PermissionScreen
import org.familycircle.android.ui.settings.SettingsScreen
import kotlin.reflect.KClass

/**
 * Top-level state machine: server → sign-in → permission walkthrough → main app. The stage is
 * derived from persisted state, so process death or an expired session always lands on the right
 * screen without a navigation back stack to repair.
 */
@Composable
fun AppRoot() {
    val container = LocalAppContainer.current
    val prefs by container.prefs.state.collectAsStateWithLifecycle()
    val auth by container.auth.state.collectAsStateWithLifecycle()
    val lastEnd by container.auth.lastEnd.collectAsStateWithLifecycle()

    when {
        prefs.serverUrl == null -> ServerScreen()
        auth == AuthSession.State.LoggedOut ->
            LoginScreen(serverUrl = prefs.serverUrl.orEmpty(), sessionExpired = lastEnd == AuthSession.EndReason.EXPIRED)
        !prefs.permissionIntroDone -> PermissionScreen(onDone = { container.prefs.setPermissionIntroDone(true) })
        else -> MainScaffold()
    }
}

@Serializable object HomeRoute

@Serializable object FamilyRoute

@Serializable object SettingsRoute

@Serializable object PermissionsRoute

private data class Tab(val route: Any, val type: KClass<*>, @StringRes val label: Int, @DrawableRes val icon: Int)

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, R.string.nav_home, R.drawable.ic_nav_home),
    Tab(FamilyRoute, FamilyRoute::class, R.string.nav_family, R.drawable.ic_nav_family),
    Tab(SettingsRoute, SettingsRoute::class, R.string.nav_settings, R.drawable.ic_nav_settings),
)

@Composable
private fun MainScaffold() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = destination?.hierarchy?.any { it.hasRoute(tab.type) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        // The label is the accessible name; the icon is decorative.
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
            composable<HomeRoute> { HomeScreen(snackbar) }
            composable<FamilyRoute> { FamilyScreen(snackbar) }
            composable<SettingsRoute> { SettingsScreen(onOpenPermissions = { nav.navigate(PermissionsRoute) }) }
            composable<PermissionsRoute> { PermissionScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }) }
        }
    }
}
