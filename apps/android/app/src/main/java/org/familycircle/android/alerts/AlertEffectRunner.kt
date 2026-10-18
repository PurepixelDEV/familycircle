package org.familycircle.android.alerts

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.familycircle.android.FamilyCircleApp
import org.familycircle.android.tracking.TrackingController
import org.familycircle.android.tracking.TrackingRuntime
import org.familycircle.android.util.Logger

/** Turns [AlertEffect]s into notifications, ringing and fresh positions. */
class AlertEffectRunner(
    private val context: Context,
    private val ringer: Ringer,
    private val tracking: TrackingController,
    private val ownUserId: () -> String?,
    private val scope: CoroutineScope,
) : AlertEffectHandler {
    @Volatile private var locating: Job? = null

    override fun handle(effect: AlertEffect) {
        val alert = effect.alert
        when (effect) {
            is AlertEffect.Sos -> AlertNotifier.showSos(context, alert)
            is AlertEffect.SosEnded -> AlertNotifier.sosEnded(context, alert, effect.notify)
            is AlertEffect.CheckIn -> AlertNotifier.showCheckIn(context, alert)
            is AlertEffect.Place -> AlertNotifier.showPlace(context, alert, effect.arrived)
            is AlertEffect.BatteryLow -> AlertNotifier.showBattery(context, alert, shutdown = false)
            is AlertEffect.LastLocation -> AlertNotifier.showBattery(context, alert, shutdown = true)
            is AlertEffect.Ring -> ringer.start(alert.actor.displayName, self = alert.actor.userId == ownUserId())
            is AlertEffect.Locate -> locate()
        }
    }

    /**
     * "Update location now" from a family member: the same one-shot path as "send my location
     * now". Only when Android lets the app get a position right now – the location service runs,
     * "Allow all the time" is granted or the app is on screen; otherwise nothing happens (no
     * prompt in the background).
     */
    private fun locate() {
        if (locating?.isActive == true) return
        locating = scope.launch {
            if (!canLocateNow()) {
                Logger.i(TAG, "locate request ignored: no location access right now")
                return@launch
            }
            val result = tracking.sendNow()
            Logger.i(TAG, "locate request answered (${result.javaClass.simpleName})")
        }
    }

    private fun canLocateNow(): Boolean {
        if (!tracking.hasForegroundPermission() || !tracking.isLocationEnabled()) return false
        return TrackingRuntime.running.value || tracking.hasBackgroundPermission() || appOnScreen()
    }

    private fun appOnScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Lets a short-lived caller (worker) wait until ringing / locating has finished. */
    suspend fun awaitIdle(maxMs: Long) {
        withTimeoutOrNull(maxMs) {
            while (ringer.ringing.value || locating?.isActive == true) delay(500)
        }
    }

    private companion object {
        const val TAG = "AlertEffects"
    }
}

/** "Stopp" on the ringing notification (and swiping it away). */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_RING) {
            (context.applicationContext as FamilyCircleApp).container.ringer.stop()
        }
    }

    companion object {
        const val ACTION_STOP_RING = "org.familycircle.android.action.STOP_RING"
    }
}
