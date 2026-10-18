package org.familycircle.android

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.familycircle.android.api.ApiClient
import org.familycircle.android.api.AuthSession
import org.familycircle.android.data.AccountRepository
import org.familycircle.android.data.prefs.AppPreferences
import org.familycircle.android.data.prefs.TrackingProblem
import org.familycircle.android.data.queue.AppDatabase
import org.familycircle.android.data.queue.RoomLocationQueue
import org.familycircle.android.data.security.KeystoreTokenStore
import org.familycircle.android.sync.SyncManager
import org.familycircle.android.sync.UploadScheduler
import org.familycircle.android.sync.Uploader
import org.familycircle.android.tracking.LocationRecorder
import org.familycircle.android.tracking.TrackingController
import org.familycircle.android.util.Logger

/** Manual dependency container (no DI framework needed at this size). One instance per process. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs = AppPreferences(app)
    val auth = AuthSession(KeystoreTokenStore(app))
    val api = ApiClient(ApiClient.defaultHttpClient(), auth, { prefs.current.serverUrl }, BuildConfig.VERSION_NAME)

    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "familycircle.db").build()
    val dao = db.pendingLocations()
    private val uploader = Uploader(RoomLocationQueue(dao), api)
    val sync = SyncManager(app, uploader, prefs)

    val recorder = LocationRecorder(app, dao, prefs)
    val tracking = TrackingController(app, prefs, recorder, sync)
    val account = AccountRepository(app, api, auth, prefs, dao, recorder, tracking, BuildConfig.VERSION_NAME)

    init {
        // If the server ends the session (refresh token rejected), stop sharing: without tokens no
        // point can be uploaded, and the user has to sign in again anyway.
        scope.launch {
            auth.state.drop(1).collect { state ->
                if (state == AuthSession.State.LoggedOut && auth.lastEnd.value == AuthSession.EndReason.EXPIRED) {
                    Logger.i("Session", "session expired, stopping tracking")
                    if (prefs.current.trackingDesired) tracking.stop(TrackingProblem.SIGNED_OUT)
                }
            }
        }
    }

    /** Hands leftover points to WorkManager when nothing else will upload them. */
    fun scheduleCatchUpIfQueued() {
        scope.launch {
            if (auth.isLoggedIn && dao.count() > 0) UploadScheduler.enqueue(app)
        }
    }
}
