package org.familycircle.android.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.familycircle.android.FamilyCircleApp
import org.familycircle.android.util.Logger

/**
 * Resumes sharing after a reboot – only if the user had tracking switched on **and** granted
 * "Allow all the time" (Android requires background location to start a location foreground
 * service from the background, even from `BOOT_COMPLETED`). Otherwise a notification asks the
 * user to open the app once.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as FamilyCircleApp).container
        container.scheduleCatchUpIfQueued()
        val prefs = container.prefs.current
        if (!prefs.trackingDesired || !container.auth.isLoggedIn) return

        val tracking = container.tracking
        if (tracking.hasForegroundPermission() && tracking.hasBackgroundPermission()) {
            try {
                ContextCompat.startForegroundService(context, LocationService.startIntent(context))
                Logger.i(TAG, "resumed after boot")
                return
            } catch (e: Exception) {
                Logger.w(TAG, "could not resume after boot", e)
            }
        }
        Notifications.ensureChannels(context)
        Notifications.showResumeHint(context)
    }

    private companion object {
        const val TAG = "Boot"
    }
}
