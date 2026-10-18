package org.familycircle.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** "Show this person on the map" from a notification (SOS: their SOS position). */
data class MapFocusRequest(val userId: String, val sos: Boolean, val nonce: Long)

/** Memory-only hand-over from [org.familycircle.android.MainActivity] to the map screen. */
class MapFocusHandoff {
    private val _request = MutableStateFlow<MapFocusRequest?>(null)
    val request: StateFlow<MapFocusRequest?> = _request.asStateFlow()

    fun offer(userId: String, sos: Boolean) {
        _request.value = MapFocusRequest(userId, sos, System.nanoTime())
    }

    /** Takes [expected] if it is still pending (a newer request is never lost). */
    fun take(expected: MapFocusRequest): Boolean = _request.compareAndSet(expected, null)
}
