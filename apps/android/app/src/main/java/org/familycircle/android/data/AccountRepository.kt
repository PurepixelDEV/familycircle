package org.familycircle.android.data

import android.content.Context
import org.familycircle.android.api.ApiClient
import org.familycircle.android.api.ApiException
import org.familycircle.android.api.AuthSession
import org.familycircle.android.api.InstanceInfo
import org.familycircle.android.api.ServerUrl
import org.familycircle.android.api.TokenRequest
import org.familycircle.android.data.prefs.AppPreferences
import org.familycircle.android.data.queue.PendingLocationDao
import org.familycircle.android.map.AvatarRepository
import org.familycircle.android.sync.UploadScheduler
import org.familycircle.android.tracking.LocationRecorder
import org.familycircle.android.tracking.TrackingController
import org.familycircle.android.util.Logger

/** Server selection, sign-in/registration and sign-out, including the local clean-up they imply. */
class AccountRepository(
    private val context: Context,
    private val api: ApiClient,
    private val auth: AuthSession,
    private val prefs: AppPreferences,
    private val dao: PendingLocationDao,
    private val recorder: LocationRecorder,
    private val tracking: TrackingController,
    private val avatars: AvatarRepository,
    private val appVersion: String,
) {
    suspend fun verifyServer(server: ServerUrl.Result.Valid): InstanceInfo = api.instance(server.url)

    fun saveServer(server: ServerUrl.Result.Valid, info: InstanceInfo) {
        prefs.setServer(server.url, server.cleartext, info.version, info.mapStyleUrl)
    }

    suspend fun login(email: String, password: String, deviceName: String) = signIn(
        TokenRequest(
            grantType = TokenRequest.GRANT_PASSWORD,
            email = email.trim(),
            password = password,
            deviceName = deviceName.trim(),
            appVersion = appVersion,
        ),
    )

    suspend fun register(inviteCode: String, displayName: String, email: String, password: String, deviceName: String) =
        signIn(
            TokenRequest(
                grantType = TokenRequest.GRANT_PASSWORD,
                email = email.trim(),
                password = password,
                deviceName = deviceName.trim(),
                appVersion = appVersion,
                inviteCode = inviteCode.trim(),
                displayName = displayName.trim(),
            ),
        )

    /**
     * Connects this phone with a pairing code from a QR code (docs/QR_FORMAT.md). The server is only
     * saved after the code was accepted; the code itself is never stored or logged.
     */
    suspend fun pair(server: ServerUrl.Result.Valid, info: InstanceInfo, code: String, deviceName: String) {
        val request = TokenRequest.pairing(code.trim(), deviceName.trim(), appVersion)
        val response = api.pair(server.url, request)
        forgetPreviousAccountIfDifferent(response.user.id)
        saveServer(server, info)
        prefs.setUser(response.user.id, response.user.email, response.user.displayName, response.deviceId, request.deviceName!!)
        refreshSettingsCache()
    }

    private suspend fun signIn(request: TokenRequest) {
        val response = api.login(request)
        forgetPreviousAccountIfDifferent(response.user.id)
        prefs.setUser(response.user.id, response.user.email, response.user.displayName, response.deviceId, request.deviceName!!)
        refreshSettingsCache()
    }

    private suspend fun forgetPreviousAccountIfDifferent(userId: String) {
        val previousUser = prefs.current.userId
        if (previousUser != null && previousUser != userId) {
            // Queued points belong to the previous account; never upload them under another one.
            Logger.i(TAG, "different account signed in, clearing local queue")
            dao.clear()
            recorder.reset()
            avatars.clear()
            prefs.clearAccount()
        }
    }

    /** Caches `share_battery` locally (best effort). */
    suspend fun refreshSettingsCache() {
        try {
            prefs.setShareBattery(api.settings().shareBattery)
        } catch (e: ApiException) {
            Logger.w(TAG, "could not load settings", e)
        }
    }

    /**
     * Signs out: stops tracking, revokes the session on the server (best effort), deletes tokens,
     * queued points and account data on the device. The server URL is kept.
     */
    suspend fun logout() {
        tracking.stop()
        try {
            api.logout()
        } catch (e: ApiException) {
            Logger.w(TAG, "server logout failed; clearing locally anyway", e)
        }
        clearLocalSession()
    }

    /** "Change server" = sign out + forget the server. */
    suspend fun changeServer() {
        if (auth.isLoggedIn) logout() else clearLocalSession()
        prefs.clearAll()
    }

    private suspend fun clearLocalSession() {
        auth.clear(AuthSession.EndReason.LOGOUT)
        UploadScheduler.cancel(context)
        dao.clear()
        recorder.reset()
        avatars.clear()
        prefs.clearAccount()
    }

    private companion object {
        const val TAG = "Account"
    }
}
