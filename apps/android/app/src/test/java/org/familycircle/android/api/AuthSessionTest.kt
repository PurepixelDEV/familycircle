package org.familycircle.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private val me = Me("u1", "a@example.org", "A", "de", false, false, "2026-01-01T00:00:00Z")

private fun tokens(n: Int) = TokenResponse("access$n", "refresh$n", "Bearer", 3600, "d1", me)

class AuthSessionTest {

    @Test
    fun `concurrent 401s trigger exactly one refresh`() = runBlocking {
        val store = InMemoryTokenStore(Tokens("access0", "refresh0", 0))
        val session = AuthSession(store)
        val refreshes = AtomicInteger()
        session.refresher = { rt ->
            assertEquals("refresh0", rt)
            delay(50) // widen the race window
            tokens(refreshes.incrementAndGet())
        }
        val results = (1..20).map {
            async(Dispatchers.Default) { session.refreshAfterUnauthorized("access0") }
        }.awaitAll()
        assertEquals(1, refreshes.get())
        assertTrue(results.all { it == "access1" })
        assertEquals("refresh1", store.load()!!.refreshToken)
    }

    @Test
    fun `a later 401 with the new token refreshes again`() = runTest {
        val session = AuthSession(InMemoryTokenStore(Tokens("access0", "refresh0", 0)))
        var n = 0
        session.refresher = { tokens(++n) }
        assertEquals("access1", session.refreshAfterUnauthorized("access0"))
        assertEquals("access2", session.refreshAfterUnauthorized("access1"))
        assertEquals(2, n)
    }

    @Test
    fun `token_reused clears tokens and reports expiry`() = runTest {
        val store = InMemoryTokenStore(Tokens("access0", "refresh0", 0))
        val session = AuthSession(store)
        session.refresher = { throw ApiException.Server(401, "token_reused", "reused") }
        try {
            session.refreshAfterUnauthorized("access0")
            fail("expected SessionExpired")
        } catch (e: ApiException.SessionExpired) {
            assertEquals("token_reused", e.code)
        }
        assertNull(store.load())
        assertFalse(session.isLoggedIn)
        assertEquals(AuthSession.State.LoggedOut, session.state.value)
        assertEquals(AuthSession.EndReason.EXPIRED, session.lastEnd.value)
    }

    @Test
    fun `invalid_grant (as Unauthorized) also ends the session`() = runTest {
        val store = InMemoryTokenStore(Tokens("a", "r", 0))
        val session = AuthSession(store)
        session.refresher = { throw ApiException.Unauthorized("invalid_grant", null) }
        try {
            session.refreshAfterUnauthorized("a")
            fail()
        } catch (e: ApiException.SessionExpired) {
            assertEquals("invalid_grant", e.code)
        }
        assertNull(store.load())
    }

    @Test
    fun `network error during refresh keeps tokens`() = runTest {
        val store = InMemoryTokenStore(Tokens("a", "r", 0))
        val session = AuthSession(store)
        session.refresher = { throw ApiException.Network(IOException("offline")) }
        try {
            session.refreshAfterUnauthorized("a")
            fail()
        } catch (_: ApiException.Network) {
        }
        assertEquals("r", store.load()!!.refreshToken)
        assertTrue(session.isLoggedIn)
    }

    @Test
    fun `server error during refresh keeps tokens`() = runTest {
        val store = InMemoryTokenStore(Tokens("a", "r", 0))
        val session = AuthSession(store)
        session.refresher = { throw ApiException.Server(503, "internal", null) }
        try {
            session.refreshAfterUnauthorized("a")
            fail()
        } catch (_: ApiException.Server) {
        }
        assertTrue(session.isLoggedIn)
    }

    @Test
    fun `tokens never appear in toString`() {
        val t = Tokens("secret-access", "secret-refresh", 1)
        assertFalse(t.toString().contains("secret"))
        assertFalse(tokens(1).toString().contains("access1"))
        assertFalse(TokenRequest("password", email = "a@b.c", password = "pw-secret").toString().contains("pw-secret"))
    }
}
