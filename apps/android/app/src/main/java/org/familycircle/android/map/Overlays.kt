package org.familycircle.android.map

import org.familycircle.android.api.Alert
import org.familycircle.android.api.Place

/** A place (geofence) as drawn on the map. */
data class PlaceShape(val id: String, val name: String, val lat: Double, val lon: Double, val radiusM: Int) {
    companion object {
        fun from(place: Place) = PlaceShape(place.id, place.name, place.lat, place.lon, place.radiusM)
    }
}

/** Red pin of another member's active SOS at the position sent with it. */
data class SosPin(val userId: String, val name: String, val lat: Double, val lon: Double) {
    companion object {
        /** Null when the SOS has no position (nobody knows where the person is). */
        fun from(alert: Alert): SosPin? =
            alert.location?.let { SosPin(alert.actor.userId, alert.actor.displayName, it.lat, it.lon) }
    }
}
