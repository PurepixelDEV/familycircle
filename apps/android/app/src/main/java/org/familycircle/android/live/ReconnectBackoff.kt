package org.familycircle.android.live

/**
 * Exponential backoff with "equal jitter" for WebSocket reconnects: the ceiling doubles per attempt
 * (1 s, 2 s, 4 s … capped at [maxMs]) and the actual delay is uniformly distributed in
 * `[ceiling / 2, ceiling]`, so many phones that lost the server at the same time do not reconnect in
 * lockstep, while every phone still waits at least half the ceiling.
 */
class ReconnectBackoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
) {
    init {
        require(baseMs > 0 && maxMs >= baseMs)
    }

    /** Upper bound for [attempt] (0-based). Never overflows for large attempts. */
    fun ceilingMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 30)
        val value = baseMs shl shift
        return if (value <= 0 || value > maxMs) maxMs else value
    }

    /** @param random a value in `[0, 1)`, injected for tests. */
    fun delayMs(attempt: Int, random: Double): Long {
        val ceiling = ceilingMs(attempt)
        val half = ceiling / 2
        return half + (half * random.coerceIn(0.0, 1.0)).toLong()
    }
}
