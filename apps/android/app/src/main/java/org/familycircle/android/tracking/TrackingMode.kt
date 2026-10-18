package org.familycircle.android.tracking

/**
 * User-selectable tracking modes. The numbers in [TrackingPolicy] are *targets*: Android, the
 * location provider and the device's power management decide what is actually delivered.
 */
enum class TrackingMode {
    /** No location is recorded. */
    OFF,

    /** ≈ every 15 min or 250 m, low-power quality (network/passive location preferred). */
    SAVER,

    /** ≈ every 2 min or 50 m, balanced power/accuracy. Default. */
    BALANCED,

    /** ≈ every 15 s or 10 m, high accuracy (GPS). Falls back to [BALANCED] after 30 minutes. */
    LIVE;

    /** One step less power-hungry; never drops below [SAVER] (or leaves [OFF]). */
    fun stepDown(): TrackingMode = when (this) {
        LIVE -> BALANCED
        BALANCED -> SAVER
        SAVER -> SAVER
        OFF -> OFF
    }

    companion object {
        val DEFAULT = BALANCED

        fun fromName(name: String?): TrackingMode? = entries.firstOrNull { it.name == name }
    }
}

/** Motion classes as defined by the API (`Motion` schema). */
enum class Motion(val apiValue: String) {
    UNKNOWN("unknown"),
    STATIONARY("stationary"),
    WALKING("walking"),
    DRIVING("driving"),
}
