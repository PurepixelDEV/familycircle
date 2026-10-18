package org.familycircle.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import org.familycircle.android.alerts.AlertNotifier
import org.familycircle.android.api.QrLink
import org.familycircle.android.ui.AppRoot
import org.familycircle.android.ui.components.LocalAppContainer
import org.familycircle.android.ui.theme.FamilyCircleTheme
import org.familycircle.android.util.Logger

class MainActivity : ComponentActivity() {
    private val container get() = (application as FamilyCircleApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a configuration change the same intent is delivered again: handle it only once.
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            FamilyCircleTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** `familycircle://link?u=…` from the browser; the screens pick it up from the handoff. */
    private fun handleLink(intent: Intent?) {
        if (intent?.action == AlertNotifier.ACTION_STOP_RING_AND_OPEN) {
            container.ringer.stop()
            return
        }
        if (intent?.action == AlertNotifier.ACTION_SHOW_ON_MAP) {
            // Tapped alert notification (SOS, check-in, place, battery): open the map on that person.
            // Deliberately no showWhenLocked – the family map is not shown above the lock screen.
            intent.getStringExtra(AlertNotifier.EXTRA_USER_ID)?.let { userId ->
                container.mapFocus.offer(userId, sos = intent.getBooleanExtra(AlertNotifier.EXTRA_SOS, false))
            }
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = intent.dataString?.let(QrLink::parseAppLink)
        if (link == null) {
            Logger.w("Link", "ignored app link that is not a FamilyCircle invite/pairing link")
            return
        }
        container.onboarding.offerLink(link)
    }
}
