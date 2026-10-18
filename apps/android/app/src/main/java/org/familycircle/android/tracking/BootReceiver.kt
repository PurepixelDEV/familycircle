package org.familycircle.android.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.familycircle.android.FamilyCircleApp

/**
 * Resumes sharing after a reboot or an app update – only if the user had tracking switched on
 * **and** granted "Allow all the time" (Android requires background location to start a location
 * foreground service from the background, even from these broadcasts). Otherwise a notification
 * asks the user to open the app once.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ResumeReason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> ResumeReason.APP_UPDATED
            else -> return
        }
        (context.applicationContext as FamilyCircleApp).container.scheduleCatchUpIfQueued()
        BackgroundResume.tryResume(context, reason)
    }
}
