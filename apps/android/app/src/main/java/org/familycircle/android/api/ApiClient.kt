package org.familycircle.android.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.familycircle.android.sync.LocationApi
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for the FamilyCircle REST API (`/api/v1`).
 *
 * * Every request carries `X-FC-CSRF: 1`: the server requires it on all non-GET requests without a
 *   bearer token (e.g. `POST /auth/token`) and ignores it otherwise.
 * * Authenticated calls retry **once** after a 401, using [AuthSession.refreshAfterUnauthorized]
 *   which serialises refreshes.
 * * Redirects are not followed: a redirect (e.g. http → https) surfaces as an error so the user
 *   fixes the URL instead of the app silently changing scheme or host.
 *
 * @param baseUrlProvider normalised server URL (see [ServerUrl]), e.g. `https://fc.example.org`.
 */
class ApiClient(
    private val http: OkHttpClient,
    private val auth: AuthSession,
    private val baseUrlProvider: () -> String?,
    appVersion: String,
    private val json: Json = ApiJson,
) : LocationApi {

    /** Also used by [LiveSocket] for the WebSocket handshake. */
    val userAgent = "FamilyCircle-Android/$appVersion"

    init {
        auth.refresher = { refreshToken ->
            issueToken(TokenRequest(grantType = TokenRequest.GRANT_REFRESH, refreshToken = refreshToken))
        }
    }

    // ---------------------------------------------------------------- public (no auth)

    /** `GET /instance` against an explicit base URL (used before the URL is saved). */
    suspend fun instance(baseUrl: String): InstanceInfo =
        decode(raw("GET", listOf("instance"), null, bearer = null, baseUrl = baseUrl))

    /** Password grant (login or, with invite code, registration). Stores the tokens on success. */
    suspend fun login(request: TokenRequest): TokenResponse {
        require(request.grantType == TokenRequest.GRANT_PASSWORD)
        val response = issueToken(request)
        auth.onTokensIssued(response)
        return response
    }

    /**
     * Pairing grant (`grant_type=pairing_code`, docs/QR_FORMAT.md) against an explicit [baseUrl]:
     * the server is only saved after the code was accepted. Stores the tokens on success.
     * An invalid/expired/used code is a 401 `invalid_grant` ([ApiException.Unauthorized]).
     */
    suspend fun pair(baseUrl: String, request: TokenRequest): TokenResponse {
        require(request.grantType == TokenRequest.GRANT_PAIRING)
        val response: TokenResponse =
            decode(raw("POST", listOf("auth", "token"), encode(request), bearer = null, baseUrl = baseUrl))
        auth.onTokensIssued(response)
        return response
    }

    private suspend fun issueToken(request: TokenRequest): TokenResponse =
        decode(raw("POST", listOf("auth", "token"), encode(request), bearer = null))

    // ---------------------------------------------------------------- authenticated

    suspend fun me(): Me = decode(authed("GET", listOf("me")))

    suspend fun settings(): PrivacySettings = decode(authed("GET", listOf("me", "settings")))

    suspend fun updateSettings(update: PrivacySettingsUpdate): PrivacySettings =
        decode(authed("PATCH", listOf("me", "settings"), encode(update)))

    suspend fun families(): List<FamilySummary> =
        decode<FamiliesResponse>(authed("GET", listOf("families"))).families

    suspend fun family(familyId: String): Family = decode(authed("GET", listOf("families", familyId)))

    suspend fun familyMap(familyId: String): FamilyMap =
        decode(authed("GET", listOf("families", familyId, "map")))

    suspend fun shares(): Shares = decode(authed("GET", listOf("shares")))

    suspend fun grantShare(userId: String) {
        expectNoContent(authed("PUT", listOf("shares", userId), body = ""))
    }

    suspend fun revokeShare(userId: String) {
        expectNoContent(authed("DELETE", listOf("shares", userId)))
    }

    /** `DELETE /auth/session` – revokes this device session on the server. */
    suspend fun logout() {
        expectNoContent(authed("DELETE", listOf("auth", "session")))
    }

    override suspend fun uploadLocations(points: List<LocationPointDto>): LocationUploadResult =
        decode(authed("POST", listOf("locations"), encode(LocationUpload(points))))

    /**
     * `GET /users/{id}/avatar?v={version}` – JPEG bytes, or null when the user has no picture or is
     * not visible to us (404).
     */
    suspend fun avatar(userId: String, version: Long?): ByteArray? {
        val response = authed(
            "GET",
            listOf("users", userId, "avatar"),
            query = version?.let { mapOf("v" to it.toString()) },
            accept = "image/*",
        )
        if (response.code == 404) return null
        if (response.code !in 200..299) throw toError(response)
        return response.bytes
    }

    /**
     * Request for the live WebSocket (`GET /api/v1/ws`; OkHttp upgrades it, `https` = `wss`).
     * Null when no server is configured.
     */
    fun liveSocketRequest(accessToken: String): Request? {
        val base = baseUrlProvider() ?: return null
        val url = try {
            buildUrl(base, listOf("ws"), null)
        } catch (_: ApiException.NoServer) {
            return null
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", userAgent)
            .build()
    }

    // ---------------------------------------------------------------- plumbing

    internal class RawResponse(val code: Int, val bytes: ByteArray, val retryAfter: String?) {
        val body: String get() = bytes.toString(Charsets.UTF_8)
    }

    private suspend fun authed(
        method: String,
        segments: List<String>,
        body: String? = null,
        query: Map<String, String>? = null,
        accept: String = "application/json",
    ): RawResponse {
        val token = auth.accessToken() ?: throw ApiException.NotLoggedIn()
        val first = raw(method, segments, body, bearer = token, query = query, accept = accept)
        if (first.code != 401) return first
        val refreshed = auth.refreshAfterUnauthorized(token)
        val second = raw(method, segments, body, bearer = refreshed, query = query, accept = accept)
        if (second.code == 401) {
            val err = parseError(second.body)
            throw ApiException.Unauthorized(err?.code, err?.message)
        }
        return second
    }

    private suspend fun raw(
        method: String,
        segments: List<String>,
        body: String?,
        bearer: String?,
        baseUrl: String? = null,
        query: Map<String, String>? = null,
        accept: String = "application/json",
    ): RawResponse {
        val url = buildUrl(baseUrl ?: baseUrlProvider() ?: throw ApiException.NoServer(), segments, query)
        val requestBody: RequestBody? = when {
            body == null && method in METHODS_REQUIRING_BODY -> EMPTY_BODY
            body == null -> null
            body.isEmpty() -> EMPTY_BODY
            else -> body.toRequestBody(JSON_MEDIA_TYPE)
        }
        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("Accept", accept)
            .header("X-FC-CSRF", "1")
            .header("User-Agent", userAgent)
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        return try {
            http.newCall(request).awaitRaw()
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
    }

    private fun buildUrl(base: String, segments: List<String>, query: Map<String, String>?): HttpUrl {
        val builder = (base.toHttpUrlOrNull() ?: throw ApiException.NoServer()).newBuilder()
        builder.addPathSegment("api").addPathSegment("v1")
        segments.forEach { builder.addPathSegment(it) }
        query?.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private inline fun <reified T> encode(value: T): String = json.encodeToString(serializer<T>(), value)

    private inline fun <reified T> decode(response: RawResponse): T = decodeWith(serializer(), response)

    private fun <T> decodeWith(deserializer: KSerializer<T>, response: RawResponse): T {
        if (response.code !in 200..299) throw toError(response)
        return try {
            json.decodeFromString(deserializer, response.body)
        } catch (e: SerializationException) {
            throw ApiException.InvalidResponse(e)
        } catch (e: IllegalArgumentException) {
            throw ApiException.InvalidResponse(e)
        }
    }

    private fun expectNoContent(response: RawResponse) {
        if (response.code !in 200..299) throw toError(response)
    }

    private fun toError(response: RawResponse): ApiException {
        val err = parseError(response.body)
        if (response.code == 401) return ApiException.Unauthorized(err?.code, err?.message)
        return ApiException.Server(
            status = response.code,
            code = err?.code,
            serverMessage = err?.message,
            fields = err?.fields.orEmpty(),
            retryAfterSec = response.retryAfter?.trim()?.toLongOrNull(),
        )
    }

    private fun parseError(body: String): ErrorDetail? = try {
        json.decodeFromString(ErrorBody.serializer(), body).error
    } catch (_: Exception) {
        null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** Executes the call asynchronously; the body is read on OkHttp's thread. Cancels with the coroutine. */
private suspend fun Call.awaitRaw(): ApiClient.RawResponse = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val raw = response.use {
                    ApiClient.RawResponse(it.code, it.body.bytes(), it.header("Retry-After"))
                }
                cont.resume(raw)
            } catch (e: IOException) {
                cont.resumeWithException(e)
            }
        }
    })
}
