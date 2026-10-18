package org.familycircle.android.alerts

import kotlinx.serialization.Serializable
import org.familycircle.android.api.Alert
import org.familycircle.android.api.AlertKind
import org.familycircle.android.util.Rfc3339

/**
 * What this phone knows about alerts; persisted so a restart neither repeats nor loses anything.
 *
 * @property lastId highest alert id processed – the cursor for `GET /alerts?after=`.
 * @property initialized the first catch-up after sign-in is done (it only looks for active SOS).
 * @property recentIds ids processed recently (dedup between live delivery and catch-up).
 * @property activeSos unresolved SOS of other people, at most one (the newest) per person.
 * @property ownSos my own unresolved SOS (sent from this or another of my devices).
 */
@Serializable
data class AlertState(
    val lastId: Long = 0,
    val initialized: Boolean = false,
    val recentIds: List<Long> = emptyList(),
    val activeSos: List<Alert> = emptyList(),
    val ownSos: Alert? = null,
)

/**
 * @param initial first catch-up after sign-in: nothing old is announced, only SOS that are still
 *        active raise the alarm.
 */
data class AlertContext(
    val nowMs: Long,
    val ownDeviceId: String?,
    val initial: Boolean = false,
)

/** What the app has to do for an alert (executed by [AlertEffectHandler]). */
sealed interface AlertEffect {
    val alert: Alert

    /** Someone else's active SOS: alarm notification. */
    data class Sos(override val alert: Alert) : AlertEffect

    /** That person's SOS is over: close the alarm; [notify] = also say "… is safe". */
    data class SosEnded(override val alert: Alert, val notify: Boolean) : AlertEffect

    data class CheckIn(override val alert: Alert) : AlertEffect

    data class Place(override val alert: Alert, val arrived: Boolean) : AlertEffect

    data class BatteryLow(override val alert: Alert) : AlertEffect

    data class LastLocation(override val alert: Alert) : AlertEffect

    /** Ring loudly (active request, not sent by this phone). */
    data class Ring(override val alert: Alert) : AlertEffect

    /** Send a fresh position now (active request, not sent by this phone). */
    data class Locate(override val alert: Alert) : AlertEffect
}

data class AlertBatch(val state: AlertState, val effects: List<AlertEffect>)

/**
 * The delivery rules of docs/ALERTS.md as a pure function (unit-tested):
 *
 * * Every alert is handled once (dedup by id across WebSocket and catch-up); the cursor only grows.
 * * Ring/locate act only while `active` (the server evaluates expiry when it sends or lists the
 *   alert) and never when this phone sent the request itself (`from_device_id`).
 * * Own alerts (`mine`) never notify; they only tell all my devices whether my SOS is active.
 * * Informational alerts older than [MAX_NOTIFY_AGE_MS] are not announced any more (a phone that
 *   was off for days should not replay old arrivals), an active SOS always is.
 * * The first catch-up after sign-in announces nothing except SOS that are still active.
 */
object AlertProcessor {
    const val MAX_RECENT = 300
    const val SOS_ACTIVE_MS = 24L * 60 * 60 * 1000
    const val MAX_NOTIFY_AGE_MS = 24L * 60 * 60 * 1000

    fun process(state: AlertState, alerts: List<Alert>, ctx: AlertContext): AlertBatch {
        var s = expire(state, ctx.nowMs)
        val seen = s.recentIds.toMutableSet()
        val recent = ArrayDeque(s.recentIds)
        val effects = mutableListOf<AlertEffect>()
        for (alert in alerts.sortedBy { it.id }) {
            if (!seen.add(alert.id)) continue
            recent.addLast(alert.id)
            while (recent.size > MAX_RECENT) seen.remove(recent.removeFirst())
            val (next, effect) = apply(s, alert, ctx)
            s = next.copy(lastId = maxOf(next.lastId, alert.id))
            if (effect != null) effects += effect
        }
        return AlertBatch(s.copy(recentIds = recent.toList()), effects)
    }

    /** Drops SOS older than 24 h (the server treats them as inactive as well). */
    fun expire(state: AlertState, nowMs: Long): AlertState {
        fun fresh(a: Alert) = (Rfc3339.parseMillis(a.createdAt) ?: nowMs) > nowMs - SOS_ACTIVE_MS
        val others = state.activeSos.filter(::fresh)
        val own = state.ownSos?.takeIf(::fresh)
        return if (others.size == state.activeSos.size && own == state.ownSos) state else state.copy(activeSos = others, ownSos = own)
    }

    /** My SOS was sent from this phone (`POST /sos` response; marked as mine either way – servers before the fix of 2026-10-18 answered `mine=false`). */
    fun ownSosSent(state: AlertState, alert: Alert): AlertState {
        val own = alert.copy(mine = true, active = true)
        val current = state.ownSos
        return if (current != null && current.id > own.id) state else state.copy(ownSos = own)
    }

    fun ownSosResolved(state: AlertState): AlertState = state.copy(ownSos = null)

    private fun apply(s: AlertState, a: Alert, ctx: AlertContext): Pair<AlertState, AlertEffect?> {
        val announce = !ctx.initial && !a.mine && isRecent(a, ctx.nowMs)
        val fromThisPhone = ctx.ownDeviceId != null && a.fromDeviceId == ctx.ownDeviceId
        return when (a.kind) {
            AlertKind.SOS -> when {
                a.mine && a.active -> (if ((s.ownSos?.id ?: -1) > a.id) s else s.copy(ownSos = a)) to null
                a.mine -> (if (s.ownSos?.id == a.id) s.copy(ownSos = null) else s) to null
                a.active -> {
                    val existing = s.activeSos.firstOrNull { it.actor.userId == a.actor.userId }
                    if (existing != null && existing.id > a.id) {
                        s to null
                    } else {
                        s.copy(activeSos = s.activeSos.filterNot { it.actor.userId == a.actor.userId } + a) to AlertEffect.Sos(a)
                    }
                }
                else -> {
                    // Listed after it was resolved or became too old: close a notification we showed.
                    val known = s.activeSos.any { it.id == a.id }
                    if (known) s.copy(activeSos = s.activeSos.filterNot { it.id == a.id }) to AlertEffect.SosEnded(a, notify = false) else s to null
                }
            }
            AlertKind.SOS_RESOLVED ->
                if (a.mine) {
                    s.copy(ownSos = null) to null
                } else {
                    s.copy(activeSos = s.activeSos.filterNot { it.actor.userId == a.actor.userId && it.id < a.id }) to
                        AlertEffect.SosEnded(a, notify = announce)
                }
            AlertKind.CHECK_IN -> s to (if (announce) AlertEffect.CheckIn(a) else null)
            AlertKind.ARRIVAL -> s to (if (announce) AlertEffect.Place(a, arrived = true) else null)
            AlertKind.DEPARTURE -> s to (if (announce) AlertEffect.Place(a, arrived = false) else null)
            AlertKind.BATTERY_LOW -> s to (if (announce) AlertEffect.BatteryLow(a) else null)
            AlertKind.LAST_LOCATION -> s to (if (announce) AlertEffect.LastLocation(a) else null)
            AlertKind.RING -> s to (if (!ctx.initial && a.active && !fromThisPhone) AlertEffect.Ring(a) else null)
            AlertKind.LOCATE -> s to (if (!ctx.initial && a.active && !fromThisPhone) AlertEffect.Locate(a) else null)
            else -> s to null // newer server: unknown kind
        }
    }

    private fun isRecent(a: Alert, nowMs: Long): Boolean {
        val created = Rfc3339.parseMillis(a.createdAt) ?: return true
        return nowMs - created <= MAX_NOTIFY_AGE_MS
    }
}
