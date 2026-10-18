package org.familycircle.android.map

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object MapStyle {
    /**
     * Resolves `map_style_url` from `GET /instance`: absolute http(s) URLs are used as they are,
     * a server-relative path (e.g. `/map/style.json`) is resolved against the server. Anything else
     * → null (no map, the member list still works).
     */
    fun resolve(styleUrl: String?, serverUrl: String?): String? {
        val raw = styleUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toHttpUrlOrNull()?.let { return it.toString() }
        if (raw.startsWith("/") && !raw.startsWith("//")) {
            val base = serverUrl?.toHttpUrlOrNull() ?: return null
            return base.resolve(raw)?.toString()
        }
        return null
    }
}
