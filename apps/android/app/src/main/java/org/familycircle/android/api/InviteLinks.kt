package org.familycircle.android.api

import java.net.URLEncoder

/** Builds the links the app shares (docs/QR_FORMAT.md); [QrLink.parse] reads them back. */
object InviteLinks {
    /** `<origin>/register?code=…` – the same URL as the invite QR code of the web app. */
    fun registerUrl(origin: String, code: String): String =
        "${origin.trimEnd('/')}/register?code=${URLEncoder.encode(code, "UTF-8")}"
}

/** Where a link opened from outside the app (browser, WhatsApp …) is handled. */
enum class LinkRoute {
    /** No server yet: the server screen verifies it and continues (pairing / registration). */
    SERVER_STEP,

    /** Signed out, but the link needs the server step (pairing, or another server): start over there. */
    RESTART_AT_SERVER_STEP,

    /** Signed out, invite for the saved server: registration form with the code. */
    INVITE_FORM,

    /** Signed in, invite for this server: ask whether to join the family. */
    ASK_JOIN,

    /** Signed in, pairing or plain server link of this server: nothing to do. */
    ALREADY_CONNECTED,

    /** Signed in, link of a different server: explain, change nothing. */
    OTHER_SERVER,

    /** Signed out, plain link to the saved server: the sign-in form is already shown. */
    IGNORE,
}

object LinkRouting {
    fun route(link: QrLink, savedServer: String?, loggedIn: Boolean): LinkRoute {
        if (savedServer == null) return LinkRoute.SERVER_STEP
        val same = sameServer(savedServer, link.origin)
        return if (loggedIn) {
            when {
                !same -> LinkRoute.OTHER_SERVER
                link is QrLink.Register -> LinkRoute.ASK_JOIN
                else -> LinkRoute.ALREADY_CONNECTED
            }
        } else {
            when {
                !same || link is QrLink.Pair -> LinkRoute.RESTART_AT_SERVER_STEP
                link is QrLink.Register -> LinkRoute.INVITE_FORM
                else -> LinkRoute.IGNORE
            }
        }
    }

    fun sameServer(a: String, b: String): Boolean = a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)
}
