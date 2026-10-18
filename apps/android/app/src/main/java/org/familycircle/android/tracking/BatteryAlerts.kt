package org.familycircle.android.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.BatteryAlertRequest
import org.familycircle.android.map.LocalFix
import org.familycircle.android.util.Logger

/**
 * "Battery low" decision (pure, unit-tested): true exactly once per discharge cycle, when the level
 * falls from above [thresholdPct] to [thresholdPct] or below while not charging. Charging – or a
 * level clearly above the threshold again – starts a new cycle. The first reading only sets the
 * baseline (a phone that is already low when sharing starts did not *cross* the threshold); the
 * server additionally sends at most one notice per 6 h.
 */
class LowBatteryGate(private val thresholdPct: Int = THRESHOLD_PCT) {
    private var lastPct: Int? = null
    private var sentThisCycle = false

    fun onReading(pct: Int, charging: Boolean): Boolean {
        val previous = lastPct
        lastPct = pct
        if (charging || pct > thresholdPct + REARM_MARGIN_PCT) {
            sentThisCycle = false
            return false
        }
        if (pct > thresholdPct || sentThisCycle) return false
        if (previous == null || previous <= thresholdPct) return false
        sentThisCycle = true
        return true
    }

    companion object {
        const val THRESHOLD_PCT = 15
        const val REARM_MARGIN_PCT = 5
    }
}

/**
 * Registered by the location service while sharing runs: reports low battery once per discharge
 * cycle and, best effort with a short timeout, the shutdown of the phone – both with the latest
 * fix of this phone (the server falls back to the last uploaded position).
 */
class BatteryAlertMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val latestFix: () -> LocalFix?,
    private val send: suspend (BatteryAlertRequest) -> Unit,
) {
    private val gate = LowBatteryGate()
    private var lastPct: Int? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
                Intent.ACTION_SHUTDOWN -> onShutdown()
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        // System broadcasts still reach a not-exported receiver.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        registered = false
    }

    private fun onBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val pct = (level * 100 / scale).coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        lastPct = pct
        if (gate.onReading(pct, charging)) {
            Logger.i(TAG, "battery low ($pct %), notifying family")
            scope.launch { post(BatteryAlertRequest.LOW, pct) }
        }
    }

    private fun onShutdown() {
        val pct = BatteryReader.read(context)?.percent ?: lastPct ?: return
        Logger.i(TAG, "phone shutting down, sending last location")
        val pending = try {
            receiver.goAsync()
        } catch (_: IllegalStateException) {
            null
        }
        scope.launch {
            try {
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { post(BatteryAlertRequest.SHUTDOWN, pct) }
            } finally {
                pending?.finish()
            }
        }
    }

    private suspend fun post(reason: String, pct: Int) {
        val fix = latestFix()
        val request = BatteryAlertRequest(
            reason = reason,
            batteryPct = pct,
            lat = fix?.lat,
            lon = fix?.lon,
            accuracyM = fix?.accuracyM?.takeIf { it > 0 },
        )
        try {
            send(request)
        } catch (e: ApiException) {
            Logger.w(TAG, "battery notice not sent", e)
        }
    }

    private companion object {
        const val TAG = "Battery"
        const val SHUTDOWN_TIMEOUT_MS = 3_000L
    }
}
