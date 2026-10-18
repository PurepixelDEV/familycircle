package org.familycircle.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire types of API 0.3 (docs/ALERTS.md): alerts, SOS, check-ins, battery notices, ring/locate,
 * places and shared history. Same conventions as Dto.kt.
 */

/** Alert kinds (`Alert.kind`). Unknown kinds from newer servers are ignored by the app. */
object AlertKind {
    const val SOS = "sos"
    const val SOS_RESOLVED = "sos_resolved"
    const val CHECK_IN = "check_in"
    const val ARRIVAL = "arrival"
    const val DEPARTURE = "departure"
    const val BATTERY_LOW = "battery_low"
    const val LAST_LOCATION = "last_location"
    const val RING = "ring"
    const val LOCATE = "locate"
}

@Serializable
data class AlertActor(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class AlertPlace(
    val id: String? = null,
    val name: String,
)

@Serializable
data class AlertPoint(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
)

@Serializable
data class Alert(
    val id: Long,
    val kind: String,
    val actor: AlertActor,
    @SerialName("from_device_id") val fromDeviceId: String? = null,
    val place: AlertPlace? = null,
    val location: AlertPoint? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    val message: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    /** SOS: unresolved and younger than 24 h. Ring/locate: not expired (evaluated by the server). */
    val active: Boolean = false,
    /** Sent by me (own SOS / check-in, echoed to my other devices). */
    val mine: Boolean = false,
) {
    /** Positions and messages never end up in logs. */
    override fun toString(): String = "Alert($id, $kind, active=$active, mine=$mine)"
}

@Serializable
data class AlertsResponse(
    val alerts: List<Alert>,
    @SerialName("server_time") val serverTime: String,
)

/** `201` of `POST /sos` and `POST /check-ins`. */
@Serializable
data class AlertCreated(
    val alert: Alert,
    val recipients: Int,
)

/** Optional position of `POST /sos` (`accuracy_m` omitted when unknown). */
@Serializable
data class PositionDto(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
) {
    override fun toString(): String = "PositionDto(<redacted>)"
}

@Serializable
data class CheckInRequest(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    val message: String? = null,
) {
    override fun toString(): String = "CheckInRequest(<redacted>)"
}

@Serializable
data class SosResolved(val resolved: Int)

/** `POST /battery-alerts` (device only). */
@Serializable
data class BatteryAlertRequest(
    val reason: String,
    @SerialName("battery_pct") val batteryPct: Int,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
) {
    override fun toString(): String = "BatteryAlertRequest($reason, $batteryPct)"

    companion object {
        const val LOW = "low"
        const val SHUTDOWN = "shutdown"
    }
}

/** `200 {sent:false}` (disabled / recently sent) or `201 {sent:true, recipients}`. */
@Serializable
data class BatteryAlertResult(
    val sent: Boolean,
    val recipients: Int? = null,
)

/** `202` of `POST /users/{id}/ring` and `/locate`. */
@Serializable
data class RemoteRequestResult(
    @SerialName("alert_id") val alertId: Long,
    @SerialName("expires_at") val expiresAt: String,
    /** At least one phone of the person is connected right now. */
    val online: Boolean,
)

@Serializable
data class ShareUpdate(val history: Boolean)

/** One point of `GET /users/{id}/locations` (newest first). */
@Serializable
data class HistoryPoint(
    val id: String,
    val suspect: Boolean = false,
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
    @SerialName("speed_mps") val speedMps: Double? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    val motion: String = "unknown",
    @SerialName("recorded_at") val recordedAt: String,
) {
    override fun toString(): String = "HistoryPoint($id)"
}

@Serializable
data class HistoryResponse(val points: List<HistoryPoint>)

@Serializable
data class Place(
    val id: String,
    @SerialName("family_id") val familyId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    @SerialName("radius_m") val radiusM: Int,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String,
    /** Arrival/departure alerts of this place are off for me. */
    val muted: Boolean = false,
    /** Members inside right now whose location I may see (and me). */
    val present: List<String> = emptyList(),
) {
    override fun toString(): String = "Place($id)"
}

@Serializable
data class PlacesResponse(val places: List<Place>)

/** `POST /families/{id}/places` (all fields) and `PATCH /places/{id}` (only the changed ones). */
@Serializable
data class PlaceInput(
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("radius_m") val radiusM: Int? = null,
) {
    override fun toString(): String = "PlaceInput(<redacted>)"

    companion object {
        const val MIN_RADIUS_M = 50
        const val MAX_RADIUS_M = 2000
        const val DEFAULT_RADIUS_M = 150
        const val MAX_NAME = 64
    }
}

@Serializable
data class PlaceCreated(val id: String)
