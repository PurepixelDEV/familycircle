package org.familycircle.android.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Access + refresh token pair. */
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMs: Long,
) {
    override fun toString(): String = "Tokens(<redacted>, expires=$accessExpiresAtMs)"
}

/** Persistent token storage (encrypted in the app, in-memory in tests). */
interface TokenStore {
    fun load(): Tokens?

    /** Must be durable when it returns: a rotated refresh token that is lost logs the user out. */
    fun save(tokens: Tokens)

    fun clear()
}

class InMemoryTokenStore(initial: Tokens? = null) : TokenStore {
    @Volatile private var tokens: Tokens? = initial
    override fun load(): Tokens? = tokens
    override fun save(tokens: Tokens) {
        this.tokens = tokens
    }
    override fun clear() {
        tokens = null
    }
}

/**
 * Owns the device session and serialises token refreshes.
 *
 * The server rotates the refresh token on every use and revokes the whole session when an
 * already-rotated refresh token is presented again (outside a 2-minute grace window). Concurrent
 * requests that all hit 401 must therefore trigger **exactly one** refresh: [refreshAfterUnauthorized]
 * runs under a [Mutex], and a caller whose rejected access token is no longer current simply picks
 * up the token another caller already obtained.
 */
class AuthSession(
    private val store: TokenStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class EndReason { LOGOUT, EXPIRED }

    sealed interface State {
        data object LoggedOut : State
        data object LoggedIn : State
    }

    private val mutex = Mutex()

    @Volatile private var current: Tokens? = store.load()

    private val _state = MutableStateFlow(if (current != null) State.LoggedIn else State.LoggedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set when the session ended without the user asking for it (refresh rejected). */
    private val _lastEnd = MutableStateFlow<EndReason?>(null)
    val lastEnd: StateFlow<EndReason?> = _lastEnd.asStateFlow()

    /** Performs the actual `grant_type=refresh_token` call; injected by [ApiClient]. */
    @Volatile
    var refresher: (suspend (refreshToken: String) -> TokenResponse)? = null

    fun accessToken(): String? = current?.accessToken

    val isLoggedIn: Boolean get() = current != null

    /** Stores tokens of a fresh login. */
    fun onTokensIssued(response: TokenResponse) {
        val tokens = response.toTokens(clock())
        store.save(tokens)
        current = tokens
        _lastEnd.value = null
        _state.value = State.LoggedIn
    }

    /**
     * Called after an authenticated request returned 401 for [rejectedAccessToken]. Returns a
     * (possibly already refreshed) access token to retry with.
     *
     * @throws ApiException.SessionExpired when the server rejected the refresh token (tokens cleared).
     * @throws ApiException.Network / [ApiException.Server] for transient failures (tokens kept).
     */
    suspend fun refreshAfterUnauthorized(rejectedAccessToken: String): String = mutex.withLock {
        val tokens = current ?: throw ApiException.SessionExpired(null)
        if (tokens.accessToken != rejectedAccessToken) {
            // Someone else refreshed while we were waiting for the lock.
            return tokens.accessToken
        }
        val call = refresher ?: error("AuthSession.refresher not configured")
        val response = try {
            call(tokens.refreshToken)
        } catch (e: ApiException.Server) {
            // 401 = token_reused / invalid_grant; 400 = malformed request (e.g. empty token).
            // Everything else (429, 5xx, …) is transient and keeps the tokens.
            if (e.status == 401 || e.status == 400) {
                endLocked(EndReason.EXPIRED)
                throw ApiException.SessionExpired(e.code)
            }
            throw e
        } catch (e: ApiException.Unauthorized) {
            endLocked(EndReason.EXPIRED)
            throw ApiException.SessionExpired(e.code)
        }
        val fresh = response.toTokens(clock())
        store.save(fresh)
        current = fresh
        fresh.accessToken
    }

    /** Local sign-out (after `DELETE /auth/session` or when the user changes the server). */
    suspend fun clear(reason: EndReason = EndReason.LOGOUT) = mutex.withLock { endLocked(reason) }

    private fun endLocked(reason: EndReason) {
        store.clear()
        current = null
        _lastEnd.value = reason
        _state.value = State.LoggedOut
    }

    private fun TokenResponse.toTokens(now: Long) = Tokens(
        accessToken = accessToken,
        refreshToken = refreshToken,
        accessExpiresAtMs = now + expiresIn.coerceAtLeast(0) * 1000,
    )
}
