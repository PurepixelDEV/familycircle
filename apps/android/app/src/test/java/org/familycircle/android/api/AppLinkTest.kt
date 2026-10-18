package org.familycircle.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinkTest {
    private val invite = "https://fc.example.org/register?code=K7QF-2M9X-HD4R"

    @Test
    fun `app link from the web page carries the invite`() {
        val link = QrLink.parseAppLink("familycircle://link?u=https%3A%2F%2Ffc.example.org%2Fregister%3Fcode%3DK7QF-2M9X-HD4R")
        assertEquals(QrLink.Register("https://fc.example.org", "K7QF-2M9X-HD4R"), link)
    }

    @Test
    fun `app link carries a pairing link with port and http`() {
        val link = QrLink.parseAppLink("familycircle://link?u=http%3A%2F%2F192.168.1.5%3A9092%2Fpair%3Fcode%3DAN2B-GHM2-NKHT")
        assertEquals(QrLink.Pair("http://192.168.1.5:9092", "AN2B-GHM2-NKHT"), link)
    }

    @Test
    fun `app link is validated like a QR code`() {
        assertNull(QrLink.parseAppLink("familycircle://link?u=javascript%3Aalert(1)"))
        assertNull(QrLink.parseAppLink("familycircle://link?u=https%3A%2F%2Fuser%40fc.example.org%2Fregister%3Fcode%3DK7QF-2M9X"))
        assertNull(QrLink.parseAppLink("familycircle://link?u=https%3A%2F%2Ffc.example.org%2Fregister%3Fcode%3D%3Cscript%3E"))
        assertNull(QrLink.parseAppLink("familycircle://link?u=%ZZ"))
        assertNull(QrLink.parseAppLink("familycircle://link?u=a&u=b"))
        assertNull(QrLink.parseAppLink("familycircle://link"))
        assertNull(QrLink.parseAppLink("familycircle://other?u=https%3A%2F%2Ffc.example.org%2F"))
        assertNull(QrLink.parseAppLink("https://fc.example.org/register?code=K7QF-2M9X-HD4R"))
        assertNull(QrLink.parseAppLink("familycircle://link?u=" + "a".repeat(3000)))
    }

    @Test
    fun `shared invite link round-trips through the QR parser`() {
        val url = InviteLinks.registerUrl("https://fc.example.org/", "K7QF-2M9X-HD4R")
        assertEquals(invite, url)
        assertEquals(QrLink.Register("https://fc.example.org", "K7QF-2M9X-HD4R"), QrLink.parse(url))
    }

    @Test
    fun `routes a link by app state`() {
        val reg = QrLink.Register("https://fc.example.org", "K7QF-2M9X-HD4R")
        val pair = QrLink.Pair("https://fc.example.org", "AN2B-GHM2-NKHT")
        val server = QrLink.Server("https://fc.example.org")
        val other = QrLink.Register("https://other.example.org", "K7QF-2M9X-HD4R")
        val saved = "https://FC.example.org/"

        assertEquals(LinkRoute.SERVER_STEP, LinkRouting.route(reg, null, loggedIn = false))
        assertEquals(LinkRoute.INVITE_FORM, LinkRouting.route(reg, saved, loggedIn = false))
        assertEquals(LinkRoute.RESTART_AT_SERVER_STEP, LinkRouting.route(pair, saved, loggedIn = false))
        assertEquals(LinkRoute.RESTART_AT_SERVER_STEP, LinkRouting.route(other, saved, loggedIn = false))
        assertEquals(LinkRoute.IGNORE, LinkRouting.route(server, saved, loggedIn = false))

        assertEquals(LinkRoute.ASK_JOIN, LinkRouting.route(reg, saved, loggedIn = true))
        assertEquals(LinkRoute.ALREADY_CONNECTED, LinkRouting.route(pair, saved, loggedIn = true))
        assertEquals(LinkRoute.ALREADY_CONNECTED, LinkRouting.route(server, saved, loggedIn = true))
        // Signed in: a link of another server never signs out or switches servers.
        assertEquals(LinkRoute.OTHER_SERVER, LinkRouting.route(other, saved, loggedIn = true))
    }
}
