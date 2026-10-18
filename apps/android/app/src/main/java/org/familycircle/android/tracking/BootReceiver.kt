package org.familycircle.android.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.familycircle.android.FamilyCircleApp
import org.familycircle.android.alerts.AlertService
import org.familycircle.android.alerts.AlertSync

/**
 * Resumes sharing after a reboot or an app update – only if the user had tracking switched on
 * **and** granted "Allow all the time" (Android requires background location to start a location
 * foreground service from the background, even from these broadcasts). Otherwise a notification
 * asks the user to open the app once. Also brings back the alert catch-up and, if switched on, the
 * "always receive alarms" service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ResumeReason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> ResumeReason.APP_UPDATED
            else -> return
        }
        val container = (context.applicationContext as FamilyCircleApp).container
        container.scheduleCatchUpIfQueued()
        BackgroundResume.tryResume(context, reason)
        // Alerts: the periodic catch-up is (re)scheduled when the container starts while signed in;
        // "always receive alarms" may start its foreground service from these broadcasts.
        if (container.auth.isLoggedIn) AlertSync.schedule(context)
        AlertService.startIfWanted(context)
    }
}
