package org.familycircle.android.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A scanned FamilyCircle QR code (docs/QR_FORMAT.md). Every QR code is a plain `http(s)` URL of the
 * family's own server:
 *
 * | QR content                          | Meaning                                  |
 * |-------------------------------------|------------------------------------------|
 * | `<origin>/`                         | only connect to the server → sign in     |
 * | `<origin>/pair?code=XXXX-XXXX-XXXX` | connect this phone to an existing account |
 * | `<origin>/register?code=…`          | invite → registration form               |
 *
 * `<origin>` is `scheme://host[:port]`; sub-paths are not supported, so the server is always the
 * origin of the scanned URL. Codes are short-lived secrets: they are never logged or persisted, and
 * [toString] redacts them.
 */
sealed interface QrLink {
    /** Normalised origin (no trailing slash), e.g. `https://fc.example.org` or `http://192.168.1.5:8080`. */
    val origin: String

    /** True for `http://` – the usual cleartext warning applies. */
    val cleartext: Boolean get() = origin.startsWith("http://")

    data class Server(override val origin: String) : QrLink

    class Pair(override val origin: String, val code: String) : QrLink {
        override fun toString(): String = "QrLink.Pair($origin, <code>)"
        override fun equals(other: Any?): Boolean = other is Pair && other.origin == origin && other.code == code
        override fun hashCode(): Int = 31 * origin.hashCode() + code.hashCode()
    }

    class Register(override val origin: String, val code: String) : QrLink {
        override fun toString(): String = "QrLink.Register($origin, <code>)"
        override fun equals(other: Any?): Boolean = other is Register && other.origin == origin && other.code == code
        override fun hashCode(): Int = 31 * origin.hashCode() + code.hashCode()
    }

    companion object {
        /** Codes are `XXXX-XXXX-XXXX` today; be lenient on length/grouping but strict on characters. */
        private val CODE = Regex("^[A-Za-z0-9][A-Za-z0-9-]{3,63}$")
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        private const val MAX_LENGTH = 2048

        /**
         * Parses the raw text of a QR code. Returns null for anything that is not a FamilyCircle
         * link: other schemes (`geo:`, `WIFI:`, `mailto:` …), text without a scheme, URLs with user
         * info or a fragment, `/pair` or `/register` without a valid `code`, or garbage.
         *
         * Any other path on the server (e.g. a scanned web-app address like `/map`) is treated as
         * "connect to this server" – the address is verified via `GET /instance` before it is used.
         */
        fun parse(raw: String): QrLink? {
            val text = raw.trim()
            if (text.isEmpty() || text.length > MAX_LENGTH) return null
            if (text.any { it.isWhitespace() || it.isISOControl() }) return null
            // A QR code must carry the scheme explicitly; never guess one for scanned text.
            if (!SCHEME.containsMatchIn(text)) return null
            val scheme = text.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") return null

            val url = text.toHttpUrlOrNull() ?: return null
            if (url.host.isBlank()) return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            // Also catch an empty user info ("https://@host"), which HttpUrl normalises away.
            if (text.substringAfter("://").substringBefore('/').contains('@')) return null
            if (url.encodedFragment != null) return null

            val origin = url.newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')

            val path = url.encodedPath.trimEnd('/').lowercase()
            return when (path) {
                "/pair" -> codeOf(url.queryParameterValues("code"))?.let { Pair(origin, it) }
                "/register" -> codeOf(url.queryParameterValues("code"))?.let { Register(origin, it) }
                else -> Server(origin)
            }
        }

        /** Exactly one non-blank, well-formed code. */
        private fun codeOf(values: List<String?>): String? {
            val present = values.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }
            if (present.size != 1) return null
            return present.single().takeIf { CODE.matches(it) }
        }
    }
}
