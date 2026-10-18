package org.familycircle.android.live

import org.familycircle.android.api.FamilyMap
import org.familycircle.android.api.MapMember
import org.familycircle.android.util.Rfc3339

/** The members of one family as last fetched (`GET /families/{id}/map`) plus live updates. */
data class LiveMapState(
    val familyId: String?,
    val members: List<MapMember>,
) {
    companion object {
        val EMPTY = LiveMapState(null, emptyList())

        fun from(map: FamilyMap) = LiveMapState(map.familyId, map.members)
    }
}

/** What the caller has to do after an event, besides showing the new state. */
enum class LiveEffect {
    NONE,

    /** Members of the shown family changed: refetch `GET /families/{id}/map`. */
    REFETCH_MAP,

    /** Another family changed (e.g. we were added to / removed from it): refetch the family list. */
    REFETCH_FAMILIES,
}

data class Reduction(val state: LiveMapState, val effect: LiveEffect)

/**
 * Applies WebSocket events to the map state. Pure; the app only ever shows what the server sent –
 * there is no client-side assumption about who may see whom.
 *
 * * `location` replaces a member's location only when its `recorded_at` is **newer** (out-of-order or
 *   duplicate deliveries are ignored, same rule as the server's "last location"). Events for users
 *   that are not members of the shown family are ignored (they belong to another family).
 * * `location_hidden` removes the location immediately.
 * * `family_changed` asks for a refetch.
 */
object LiveMapReducer {
    fun reduce(state: LiveMapState, event: LiveEvent): Reduction = when (event) {
        is LiveEvent.Location -> Reduction(applyLocation(state, event), LiveEffect.NONE)
        is LiveEvent.LocationHidden -> Reduction(hide(state, event.userId), LiveEffect.NONE)
        is LiveEvent.FamilyChanged -> Reduction(
            state,
            if (state.familyId != null && event.familyId == state.familyId) LiveEffect.REFETCH_MAP else LiveEffect.REFETCH_FAMILIES,
        )
        is LiveEvent.Hello, is LiveEvent.Unknown -> Reduction(state, LiveEffect.NONE)
    }

    /** Applies [events] in order (used to replay events that arrived while a fetch was running). */
    fun replay(state: LiveMapState, events: List<LiveEvent>): LiveMapState =
        events.fold(state) { acc, e -> reduce(acc, e).state }

    private fun applyLocation(state: LiveMapState, event: LiveEvent.Location): LiveMapState {
        val index = state.members.indexOfFirst { it.userId == event.userId }
        if (index < 0) return state
        val newAt = Rfc3339.parseMillis(event.location.recordedAt) ?: return state
        val current = state.members[index]
        val oldAt = current.location?.let { Rfc3339.parseMillis(it.recordedAt) }
        if (oldAt != null && newAt <= oldAt) return state
        val members = state.members.toMutableList()
        members[index] = current.copy(location = event.location)
        return state.copy(members = members)
    }

    private fun hide(state: LiveMapState, userId: String): LiveMapState {
        val index = state.members.indexOfFirst { it.userId == userId }
        if (index < 0 || state.members[index].location == null) return state
        val members = state.members.toMutableList()
        members[index] = members[index].copy(location = null)
        return state.copy(members = members)
    }
}
