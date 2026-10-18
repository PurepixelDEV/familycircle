package org.familycircle.android.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** RFC 3339 helpers. The API expects UTC timestamps; we always send millisecond precision. */
object Rfc3339 {
    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** Formats epoch millis as e.g. `2026-10-18T09:15:02.123Z`. */
    fun format(epochMillis: Long): String = FORMATTER.format(Instant.ofEpochMilli(epochMillis))

    /** Parses any RFC 3339 timestamp (any offset, 0–9 fraction digits). Returns null if invalid. */
    fun parseMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}

/** Coarse relative age buckets used by the UI ("just now", "5 min ago", …). */
sealed interface RelativeAge {
    data object JustNow : RelativeAge
    data class Minutes(val value: Int) : RelativeAge
    data class Hours(val value: Int) : RelativeAge
    data class Days(val value: Int) : RelativeAge

    companion object {
        fun of(thenMillis: Long, nowMillis: Long): RelativeAge {
            val ageSec = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
            return when {
                ageSec < 60 -> JustNow
                ageSec < 3600 -> Minutes((ageSec / 60).toInt())
                ageSec < 86_400 -> Hours((ageSec / 3600).toInt())
                else -> Days((ageSec / 86_400).toInt())
            }
        }
    }
}
