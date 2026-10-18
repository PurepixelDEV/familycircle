package org.familycircle.android.tracking

/**
 * Chooses `LocationManager` providers without Google Play Services.
 *
 * * `fused` is used when the device has it (AOSP ≥ 12 ships one; on older or de-Googled ROMs it may
 *   be missing – see docs/research/android-platform-2026-10.md).
 * * Otherwise LOW_POWER prefers `network` (+ `passive`, which only receives fixes other apps
 *   requested and costs nothing). Without a network provider (common on ROMs without a network
 *   location backend) GPS is the only option.
 * * BALANCED/HIGH_ACCURACY use GPS and network together; whichever delivers first wins and the
 *   [FixFilter] drops out-of-order fixes.
 */
object ProviderSelector {
    const val FUSED = "fused" // == LocationManager.FUSED_PROVIDER (API 31+); literal works on older APIs too
    const val GPS = "gps"
    const val NETWORK = "network"
    const val PASSIVE = "passive"

    /** @param available providers that exist on this device (not necessarily enabled). */
    fun select(available: Set<String>, quality: LocationQuality): List<String> {
        if (FUSED in available) return listOf(FUSED)
        val result = when (quality) {
            LocationQuality.LOW_POWER ->
                if (NETWORK in available) listOf(NETWORK, PASSIVE) else listOf(GPS, PASSIVE)
            LocationQuality.BALANCED, LocationQuality.HIGH_ACCURACY -> listOf(GPS, NETWORK)
        }
        return result.filter { it in available }
    }

    /** Provider for a one-shot "send my location now" request. */
    fun selectOneShot(available: Set<String>): String? = when {
        FUSED in available -> FUSED
        GPS in available -> GPS
        NETWORK in available -> NETWORK
        else -> null
    }
}
