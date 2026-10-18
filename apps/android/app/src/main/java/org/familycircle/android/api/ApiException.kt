package org.familycircle.android.api

import java.io.IOException

/** Typed failures of [ApiClient]. Messages never contain tokens, credentials or coordinates. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No connection, DNS/TLS failure, timeout. The request may or may not have reached the server. */
    class Network(cause: IOException) : ApiException("network error", cause)

    /** No tokens stored – the user has to sign in. */
    class NotLoggedIn : ApiException("not logged in")

    /** No server URL configured (or it became invalid). */
    class NoServer : ApiException("no server configured")

    /**
     * The refresh token was rejected (`token_reused`, `invalid_grant`, …). Tokens have been cleared;
     * the user must sign in again.
     */
    class SessionExpired(val code: String?) : ApiException("session expired ($code)")

    /** 401 that could not be resolved by a refresh (or a 401 from an unauthenticated call). */
    class Unauthorized(val code: String?, val serverMessage: String?) : ApiException("unauthorized ($code)")

    /**
     * Any other non-2xx response.
     * @property code machine-readable `error.code` from the body, if any.
     * @property retryAfterSec parsed `Retry-After` header (429).
     */
    class Server(
        val status: Int,
        val code: String?,
        val serverMessage: String?,
        val fields: Map<String, String> = emptyMap(),
        val retryAfterSec: Long? = null,
    ) : ApiException("http $status ($code)") {
        val isTransient: Boolean get() = status >= 500 || status == 429 || status == 408
    }

    /** 2xx with a body that does not match the contract. */
    class InvalidResponse(cause: Throwable) : ApiException("invalid response", cause)
}
