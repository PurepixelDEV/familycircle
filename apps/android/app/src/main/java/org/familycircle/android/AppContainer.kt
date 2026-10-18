package org.familycircle.android

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.familycircle.android.alerts.AlertEffectRunner
import org.familycircle.android.alerts.AlertHub
import org.familycircle.android.alerts.AlertNotifier
import org.familycircle.android.alerts.AlertService
import org.familycircle.android.alerts.AlertSync
import org.familycircle.android.alerts.PrefsAlertStateStore
import org.familycircle.android.alerts.Ringer
import org.familycircle.android.api.ApiClient
import org.familycircle.android.api.AuthSession
import org.familycircle.android.data.AccountRepository
import org.familycircle.android.data.MapFocusHandoff
import org.familycircle.android.data.OnboardingHandoff
import org.familycircle.android.live.LiveHub
import org.familycircle.android.live.LiveSocket
import org.familycircle.android.map.AvatarRepository
import java.io.File
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
    private val http = ApiClient.defaultHttpClient()
    val api = ApiClient(http, auth, { prefs.current.serverUrl }, BuildConfig.VERSION_NAME)

    /** The live WebSocket (state + diagnostics); opened only through [liveHub]. */
    val live = LiveSocket(http, auth, api::liveSocketRequest)

    /** One shared connection: open while the map is visible, sharing runs or the alert service runs. */
    val liveHub = LiveHub(live, auth, scope)
    val avatars = AvatarRepository(api, File(app.cacheDir, "avatars"))

    /** In-memory hand-over of a scanned invite code to the registration form (never persisted). */
    val onboarding = OnboardingHandoff()

    /** "Show on map" requests from notifications. */
    val mapFocus = MapFocusHandoff()

    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "familycircle.db").build()
    val dao = db.pendingLocations()
    private val uploader = Uploader(RoomLocationQueue(dao), api)
    val sync = SyncManager(app, uploader, prefs)

    val recorder = LocationRecorder(app, dao, prefs)
    val tracking = TrackingController(app, prefs, recorder, sync)

    val ringer = Ringer(app, scope)
    val alertEffects = AlertEffectRunner(app, ringer, tracking, { prefs.current.userId }, scope)

    /** Alerts (docs/ALERTS.md): live delivery, catch-up, SOS state for the UI. */
    val alerts = AlertHub(
        fetch = { after, limit -> api.alerts(after, limit) },
        store = PrefsAlertStateStore(app),
        handler = alertEffects,
        ownDeviceId = { prefs.current.deviceId },
    )

    val account = AccountRepository(app, api, auth, prefs, dao, recorder, tracking, avatars, BuildConfig.VERSION_NAME)

    init {
        AlertNotifier.ensureChannels(app)
        alerts.listen(scope, liveHub.signals)
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
        // Alerts follow the session: catch-up worker while signed in, everything forgotten after.
        scope.launch {
            auth.state.collect { state ->
                if (state == AuthSession.State.LoggedIn) {
                    AlertSync.schedule(app)
                    alerts.catchUp()
                } else {
                    AlertSync.cancel(app)
                    AlertService.stop(app)
                    ringer.stop()
                    alerts.reset()
                    AlertNotifier.cancelAll(app)
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
