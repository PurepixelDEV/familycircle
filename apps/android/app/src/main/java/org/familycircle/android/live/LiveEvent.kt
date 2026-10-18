package org.familycircle.android.live

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.familycircle.android.api.ApiJson
import org.familycircle.android.api.MemberLocation

/**
 * Server → client messages of `GET /api/v1/ws` (see the description in
 * packages/api-contract/openapi.yaml). The client never sends anything except pings.
 */
sealed interface LiveEvent {
    data class Hello(val userId: String?) : LiveEvent

    /** Only delivered to users who are currently allowed to see [userId]. */
    data class Location(val userId: String, val location: MemberLocation) : LiveEvent

    /** A share was revoked/paused or membership ended: remove the marker immediately. */
    data class LocationHidden(val userId: String) : LiveEvent

    /** Members of [familyId] changed: refetch the map. */
    data class FamilyChanged(val familyId: String) : LiveEvent

    /** A message type this app version does not know (forward compatible, ignored). */
    data class Unknown(val type: String) : LiveEvent

    companion object {
        /** Parses one text frame. Returns null for malformed JSON or a known type with missing fields. */
        fun parse(text: String): LiveEvent? {
            val obj = try {
                ApiJson.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val type = obj.string("type") ?: return null
            return when (type) {
                "hello" -> Hello(obj.string("user_id"))
                "location" -> {
                    val userId = obj.string("user_id") ?: return null
                    val loc = obj["location"] as? JsonObject ?: return null
                    val location = try {
                        ApiJson.decodeFromJsonElement(MemberLocation.serializer(), loc)
                    } catch (_: Exception) {
                        return null
                    }
                    Location(userId, location)
                }
                "location_hidden" -> obj.string("user_id")?.let(::LocationHidden)
                "family_changed" -> obj.string("family_id")?.let(::FamilyChanged)
                else -> Unknown(type)
            }
        }

        private fun JsonObject.string(key: String): String? = try {
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
