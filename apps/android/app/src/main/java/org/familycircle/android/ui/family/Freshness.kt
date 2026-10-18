package org.familycircle.android.ui.family

import org.familycircle.android.util.Rfc3339

/**
 * Freshness of a member's position: live < 2 min, recent < 15 min, stale otherwise (same rule as
 * the server, based on `recorded_at`).
 *
 * The server sends its own value, but the list stays on screen and ages: the app therefore
 * recomputes locally and always shows the **worse** of both, so an old position can never be
 * presented as live.
 */
enum class Freshness {
    LIVE, RECENT, STALE;

    companion object {
        const val LIVE_MS = 2 * 60_000L
        const val RECENT_MS = 15 * 60_000L

        fun fromApi(value: String?): Freshness? = when (value) {
            "live" -> LIVE
            "recent" -> RECENT
            "stale" -> STALE
            else -> null
        }

        /** Local rule. A timestamp slightly in the future (clock skew) counts as age 0. */
        fun fromAge(recordedAtMs: Long, nowMs: Long): Freshness {
            val age = (nowMs - recordedAtMs).coerceAtLeast(0)
            return when {
                age < LIVE_MS -> LIVE
                age < RECENT_MS -> RECENT
                else -> STALE
            }
        }

        /**
         * @param server the server's `freshness` field (unknown values are ignored).
         * @param recordedAt the server's `recorded_at`; unparsable → [STALE].
         */
        fun effective(server: String?, recordedAt: String?, nowMs: Long): Freshness {
            val recordedAtMs = Rfc3339.parseMillis(recordedAt) ?: return STALE
            val local = fromAge(recordedAtMs, nowMs)
            val remote = fromApi(server) ?: return local
            return if (remote.ordinal > local.ordinal) remote else local
        }
    }
}
