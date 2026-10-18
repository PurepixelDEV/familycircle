package org.familycircle.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire types for packages/api-contract/openapi.yaml. Field names follow the contract exactly via
 * [SerialName]. Timestamps stay strings (RFC 3339) and are parsed where needed.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true // forward compatible with newer servers
    explicitNulls = false // optional fields are omitted instead of sent as null
    encodeDefaults = true
}

@Serializable
data class InstanceInfo(
    @SerialName("setup_required") val setupRequired: Boolean,
    val version: String,
    @SerialName("map_style_url") val mapStyleUrl: String,
    @SerialName("secure_context") val secureContext: Boolean,
    @SerialName("registration_mode") val registrationMode: String,
)

@Serializable
data class TokenRequest(
    @SerialName("grant_type") val grantType: String,
    val email: String? = null,
    val password: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("invite_code") val inviteCode: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    /** Short-lived single-use code from a pairing QR code (`grant_type=pairing_code`). */
    @SerialName("pairing_code") val pairingCode: String? = null,
) {
    /** Never print credentials or codes, even in debug logs or crash reports. */
    override fun toString(): String = "TokenRequest(grantType=$grantType)"

    companion object {
        const val GRANT_PASSWORD = "password"
        const val GRANT_REFRESH = "refresh_token"
        const val GRANT_PAIRING = "pairing_code"

        /** `grant_type=pairing_code` request (docs/QR_FORMAT.md). */
        fun pairing(code: String, deviceName: String, appVersion: String) = TokenRequest(
            grantType = GRANT_PAIRING,
            pairingCode = code,
            deviceName = deviceName,
            appVersion = appVersion,
        )
    }
}

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("device_id") val deviceId: String,
    val user: Me,
) {
    override fun toString(): String = "TokenResponse(deviceId=$deviceId, expiresIn=$expiresIn)"
}

@Serializable
data class Me(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    val locale: String,
    @SerialName("is_instance_admin") val isInstanceAdmin: Boolean,
    @SerialName("has_avatar") val hasAvatar: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("avatar_version") val avatarVersion: Long? = null,
)

@Serializable
data class PrivacySettings(
    @SerialName("sharing_paused") val sharingPaused: Boolean,
    @SerialName("history_enabled") val historyEnabled: Boolean,
    @SerialName("history_retention_days") val historyRetentionDays: Int,
    @SerialName("share_battery") val shareBattery: Boolean,
    /** Family members may let my phones ring (opt-in; defaults for servers older than 0.3). */
    @SerialName("allow_ring") val allowRing: Boolean = false,
    /** People who see my location may ask for a fresh position (opt-in). */
    @SerialName("allow_locate_request") val allowLocateRequest: Boolean = false,
    /** Low-battery / shutdown notices to people who see my location (default on). */
    @SerialName("battery_alerts") val batteryAlerts: Boolean = true,
)

@Serializable
data class PrivacySettingsUpdate(
    @SerialName("sharing_paused") val sharingPaused: Boolean? = null,
    @SerialName("history_enabled") val historyEnabled: Boolean? = null,
    @SerialName("history_retention_days") val historyRetentionDays: Int? = null,
    @SerialName("share_battery") val shareBattery: Boolean? = null,
    @SerialName("allow_ring") val allowRing: Boolean? = null,
    @SerialName("allow_locate_request") val allowLocateRequest: Boolean? = null,
    @SerialName("battery_alerts") val batteryAlerts: Boolean? = null,
)

@Serializable
data class FamilySummary(
    val id: String,
    val name: String,
    val role: String,
    @SerialName("member_count") val memberCount: Int,
)

@Serializable
data class FamiliesResponse(val families: List<FamilySummary>)

@Serializable
data class InviteCreate(
    @SerialName("expires_in_hours") val expiresInHours: Int,
    @SerialName("max_uses") val maxUses: Int,
)

/** A freshly created invite; [code] is shown only once and never persisted or logged. */
@Serializable
data class InviteCreated(
    val id: String,
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("max_uses") val maxUses: Int,
) {
    override fun toString(): String = "InviteCreated($id, <code>, $expiresAt)"
}

@Serializable
data class InviteAccept(val code: String) {
    override fun toString(): String = "InviteAccept(<code>)"
}

@Serializable
data class Member(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    val color: String,
    @SerialName("has_avatar") val hasAvatar: Boolean,
    @SerialName("avatar_version") val avatarVersion: Long? = null,
    @SerialName("joined_at") val joinedAt: String,
    @SerialName("shares_with_me") val sharesWithMe: Boolean,
    @SerialName("i_share_with") val iShareWith: Boolean,
)

@Serializable
data class Family(
    val id: String,
    val name: String,
    val role: String,
    val members: List<Member>,
)

@Serializable
data class MemberLocation(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
    @SerialName("altitude_m") val altitudeM: Double? = null,
    @SerialName("speed_mps") val speedMps: Double? = null,
    @SerialName("bearing_deg") val bearingDeg: Double? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("battery_charging") val batteryCharging: Boolean? = null,
    val motion: String,
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("received_at") val receivedAt: String,
    val freshness: String,
)

@Serializable
data class MapMember(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val color: String,
    @SerialName("has_avatar") val hasAvatar: Boolean,
    @SerialName("avatar_version") val avatarVersion: Long? = null,
    @SerialName("is_self") val isSelf: Boolean,
    val location: MemberLocation? = null,
)

@Serializable
data class FamilyMap(
    @SerialName("family_id") val familyId: String,
    @SerialName("server_time") val serverTime: String,
    val members: List<MapMember>,
)

@Serializable
data class ShareEntry(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val since: String,
    /** Outgoing: I let this person see my history. Incoming: I can open this person's history now. */
    val history: Boolean = false,
)

@Serializable
data class Shares(
    val outgoing: List<ShareEntry>,
    val incoming: List<ShareEntry>,
)

/** One point of `POST /locations`. `recorded_at` is RFC 3339 UTC with milliseconds. */
@Serializable
data class LocationPointDto(
    val id: String,
    @SerialName("recorded_at") val recordedAt: String,
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
    @SerialName("altitude_m") val altitudeM: Double? = null,
    @SerialName("speed_mps") val speedMps: Double? = null,
    @SerialName("bearing_deg") val bearingDeg: Double? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("battery_charging") val batteryCharging: Boolean? = null,
    val motion: String,
)

@Serializable
data class LocationUpload(val points: List<LocationPointDto>)

@Serializable
data class Rejection(val id: String, val reason: String)

@Serializable
data class LocationUploadResult(
    val accepted: List<String>,
    val rejected: List<Rejection>,
)

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String,
    val fields: Map<String, String>? = null,
)
