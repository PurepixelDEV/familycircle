package org.familycircle.android.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validation/normalisation of the user-entered server address.
 *
 * * Surrounding whitespace is trimmed.
 * * Without a scheme, `https://` is assumed (never silently `http://`).
 * * Only `http` and `https` are accepted; user info (`user:pw@`), query and fragment are rejected.
 * * A trailing slash and a pasted `/api/v1` suffix are removed, so `https://fc.example.org/` and
 *   `https://fc.example.org/api/v1/` both become `https://fc.example.org`. Sub-paths (reverse proxy
 *   under `/familycircle`) are kept.
 */
object ServerUrl {
    sealed interface Result {
        data class Valid(val url: String, val cleartext: Boolean) : Result {
            fun httpUrl(): HttpUrl = url.toHttpUrlOrNull()!!
        }

        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason { EMPTY, UNSUPPORTED_SCHEME, MALFORMED, CREDENTIALS, QUERY_OR_FRAGMENT }

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun normalize(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Reason.EMPTY)
        val withScheme = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid(Reason.UNSUPPORTED_SCHEME)

        val parsed = withScheme.toHttpUrlOrNull() ?: return Result.Invalid(Reason.MALFORMED)
        if (parsed.host.isBlank()) return Result.Invalid(Reason.MALFORMED)
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return Result.Invalid(Reason.CREDENTIALS)
        if (parsed.encodedQuery != null || parsed.encodedFragment != null) {
            return Result.Invalid(Reason.QUERY_OR_FRAGMENT)
        }

        var path = parsed.encodedPath.trimEnd('/')
        if (path.endsWith("/api/v1")) path = path.removeSuffix("/api/v1").trimEnd('/')
        val normalized = parsed.newBuilder().encodedPath(if (path.isEmpty()) "/" else path).build()
        return Result.Valid(
            url = normalized.toString().trimEnd('/'),
            cleartext = !normalized.isHttps,
        )
    }
}
