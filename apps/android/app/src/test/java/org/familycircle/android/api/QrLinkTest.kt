package org.familycircle.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrLinkTest {
    @Test
    fun `plain origin is a server link`() {
        assertEquals(QrLink.Server("https://fc.example.org"), QrLink.parse("https://fc.example.org/"))
        assertEquals(QrLink.Server("https://fc.example.org"), QrLink.parse("https://fc.example.org"))
        assertEquals(QrLink.Server("http://192.168.1.5:8080"), QrLink.parse("http://192.168.1.5:8080/"))
    }

    @Test
    fun `pair link with code`() {
        val link = QrLink.parse("https://fc.example.org/pair?code=K7QF-2M9X-HD4R")
        assertTrue(link is QrLink.Pair)
        link as QrLink.Pair
        assertEquals("https://fc.example.org", link.origin)
        assertEquals("K7QF-2M9X-HD4R", link.code)
        assertFalse(link.cleartext)
    }

    @Test
    fun `register link with code over http keeps the port and flags cleartext`() {
        val link = QrLink.parse("http://10.0.2.2:9092/register?code=ABCD-EFGH-JKLM")
        assertEquals(QrLink.Register("http://10.0.2.2:9092", "ABCD-EFGH-JKLM"), link)
        assertTrue(link!!.cleartext)
    }

    @Test
    fun `scheme and host are case insensitive and default ports are dropped`() {
        assertEquals(QrLink.Pair("https://fc.example.org", "AAAA-BBBB-CCCC"), QrLink.parse("HTTPS://FC.Example.org:443/pair?code=AAAA-BBBB-CCCC"))
    }

    @Test
    fun `trailing slash on the path and surrounding whitespace are accepted`() {
        assertEquals(QrLink.Pair("https://fc.example.org", "AAAA-BBBB-CCCC"), QrLink.parse("  https://fc.example.org/pair/?code=AAAA-BBBB-CCCC\n"))
    }

    @Test
    fun `extra query parameters are ignored`() {
        assertEquals(QrLink.Pair("https://fc.example.org", "AAAA-BBBB-CCCC"), QrLink.parse("https://fc.example.org/pair?code=AAAA-BBBB-CCCC&utm=x"))
    }

    @Test
    fun `other paths connect to the origin only`() {
        assertEquals(QrLink.Server("https://fc.example.org"), QrLink.parse("https://fc.example.org/map?x=1"))
    }

    @Test
    fun `ipv6 origin`() {
        assertEquals(QrLink.Server("http://[::1]:9092"), QrLink.parse("http://[::1]:9092/"))
    }

    @Test
    fun `rejects other schemes`() {
        assertNull(QrLink.parse("ftp://fc.example.org/pair?code=AAAA-BBBB-CCCC"))
        assertNull(QrLink.parse("familycircle://pair?code=AAAA-BBBB-CCCC"))
        assertNull(QrLink.parse("geo:47.37,8.54"))
        assertNull(QrLink.parse("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(QrLink.parse("mailto:a@example.org"))
        assertNull(QrLink.parse("javascript:alert(1)"))
    }

    @Test
    fun `rejects text without scheme`() {
        assertNull(QrLink.parse("fc.example.org/pair?code=AAAA-BBBB-CCCC"))
    }

    @Test
    fun `rejects user info`() {
        assertNull(QrLink.parse("https://user:pw@fc.example.org/pair?code=AAAA-BBBB-CCCC"))
        assertNull(QrLink.parse("https://user@fc.example.org/"))
        assertNull(QrLink.parse("https://@fc.example.org/"))
    }

    @Test
    fun `rejects fragments`() {
        assertNull(QrLink.parse("https://fc.example.org/pair?code=AAAA-BBBB-CCCC#x"))
        assertNull(QrLink.parse("https://fc.example.org/#/pair"))
    }

    @Test
    fun `rejects missing, empty, duplicate or malformed codes`() {
        assertNull(QrLink.parse("https://fc.example.org/pair"))
        assertNull(QrLink.parse("https://fc.example.org/pair?code="))
        assertNull(QrLink.parse("https://fc.example.org/pair?code=%20"))
        assertNull(QrLink.parse("https://fc.example.org/register?invite=AAAA-BBBB-CCCC"))
        assertNull(QrLink.parse("https://fc.example.org/pair?code=AAAA&code=BBBB"))
        assertNull(QrLink.parse("https://fc.example.org/pair?code=%3Cscript%3E"))
        assertNull(QrLink.parse("https://fc.example.org/pair?code=AB"))
        assertNull(QrLink.parse("https://fc.example.org/pair?code=" + "A".repeat(65)))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(QrLink.parse(""))
        assertNull(QrLink.parse("   "))
        assertNull(QrLink.parse("hello world"))
        assertNull(QrLink.parse("https://"))
        assertNull(QrLink.parse("https:// fc.example.org/"))
        assertNull(QrLink.parse("https://fc.example.org/\u0000"))
        assertNull(QrLink.parse("https://fc.example.org/" + "a".repeat(3000)))
    }

    @Test
    fun `toString never contains the code`() {
        val link = QrLink.parse("https://fc.example.org/pair?code=SECR-ET12-3456")!!
        assertFalse(link.toString().contains("SECR"))
        val reg = QrLink.parse("https://fc.example.org/register?code=SECR-ET12-3456")!!
        assertFalse(reg.toString().contains("SECR"))
    }
}
