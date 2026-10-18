package org.familycircle.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.familycircle.android.tracking.TrackingMode

/** Why tracking is not running although the user wanted it to. Persisted so the UI can explain it. */
enum class TrackingProblem {
    PERMISSION_MISSING,
    LOCATION_DISABLED,
    NO_PROVIDER,
    START_NOT_ALLOWED,
    SIGNED_OUT,
}

/** Immutable snapshot of all non-secret app state. Tokens live in the encrypted token store. */
data class AppPrefs(
    val serverUrl: String? = null,
    val serverCleartext: Boolean = false,
    val serverVersion: String? = null,
    val mapStyleUrl: String? = null,
    val userId: String? = null,
    val userEmail: String? = null,
    val userName: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val permissionIntroDone: Boolean = false,
    val trackingDesired: Boolean = false,
    val trackingPausedByUser: Boolean = false,
    val trackingMode: TrackingMode = TrackingMode.DEFAULT,
    val liveStartedAtMs: Long? = null,
    val trackingProblem: TrackingProblem? = null,
    /** Fix time (`Location.time`) of the last recorded point. */
    val lastFixAtMs: Long? = null,
    val lastUploadAtMs: Long? = null,
    val lastUploadOk: Boolean? = null,
    /** Short machine-readable key, mapped to a localised text in the UI. */
    val lastUploadError: String? = null,
    val lastUploadCount: Int = 0,
    /** Cached `share_battery` setting; battery data is not even sent while this is false. */
    val shareBattery: Boolean = false,
    val selectedFamilyId: String? = null,
)

/**
 * SharedPreferences-backed settings with a [StateFlow] for the UI. The app runs in a single process
 * (UI, service and WorkManager), so an in-memory flow updated on every write is sufficient.
 */
class AppPreferences(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppPrefs> = _state.asStateFlow()
    val current: AppPrefs get() = _state.value

    private fun write(block: SharedPreferences.Editor.() -> Unit) = synchronized(lock) {
        sp.edit { block() }
        _state.value = read()
    }

    fun setServer(url: String, cleartext: Boolean, version: String, mapStyleUrl: String) = write {
        putString(K_SERVER_URL, url)
        putBoolean(K_SERVER_CLEARTEXT, cleartext)
        putString(K_SERVER_VERSION, version)
        putString(K_MAP_STYLE, mapStyleUrl)
    }

    fun setUser(id: String, email: String, name: String, deviceId: String, deviceName: String) = write {
        putString(K_USER_ID, id)
        putString(K_USER_EMAIL, email)
        putString(K_USER_NAME, name)
        putString(K_DEVICE_ID, deviceId)
        putString(K_DEVICE_NAME, deviceName)
    }

    fun setPermissionIntroDone(done: Boolean) = write { putBoolean(K_PERMISSION_INTRO, done) }

    fun setTracking(desired: Boolean, pausedByUser: Boolean, problem: TrackingProblem? = null) = write {
        putBoolean(K_TRACKING_DESIRED, desired)
        putBoolean(K_TRACKING_PAUSED, pausedByUser)
        if (problem == null) remove(K_TRACKING_PROBLEM) else putString(K_TRACKING_PROBLEM, problem.name)
    }

    fun setTrackingProblem(problem: TrackingProblem?) = write {
        if (problem == null) remove(K_TRACKING_PROBLEM) else putString(K_TRACKING_PROBLEM, problem.name)
    }

    fun setTrackingMode(mode: TrackingMode, liveStartedAtMs: Long?) = write {
        putString(K_TRACKING_MODE, mode.name)
        if (liveStartedAtMs == null) remove(K_LIVE_STARTED) else putLong(K_LIVE_STARTED, liveStartedAtMs)
    }

    fun setLastFix(fixTimeMs: Long) = write { putLong(K_LAST_FIX, fixTimeMs) }

    fun setUploadResult(atMs: Long, ok: Boolean, error: String?, count: Int) = write {
        putLong(K_UPLOAD_AT, atMs)
        putBoolean(K_UPLOAD_OK, ok)
        if (error == null) remove(K_UPLOAD_ERROR) else putString(K_UPLOAD_ERROR, error)
        putInt(K_UPLOAD_COUNT, count)
    }

    fun setShareBattery(share: Boolean) = write { putBoolean(K_SHARE_BATTERY, share) }

    fun setSelectedFamily(id: String?) = write {
        if (id == null) remove(K_SELECTED_FAMILY) else putString(K_SELECTED_FAMILY, id)
    }

    /** Removes everything tied to the signed-in account (keeps the server). */
    fun clearAccount() = write {
        ACCOUNT_KEYS.forEach { remove(it) }
    }

    /** Removes everything, including the server. */
    fun clearAll() = write { clear() }

    private fun read(): AppPrefs = AppPrefs(
        serverUrl = sp.getString(K_SERVER_URL, null),
        serverCleartext = sp.getBoolean(K_SERVER_CLEARTEXT, false),
        serverVersion = sp.getString(K_SERVER_VERSION, null),
        mapStyleUrl = sp.getString(K_MAP_STYLE, null),
        userId = sp.getString(K_USER_ID, null),
        userEmail = sp.getString(K_USER_EMAIL, null),
        userName = sp.getString(K_USER_NAME, null),
        deviceId = sp.getString(K_DEVICE_ID, null),
        deviceName = sp.getString(K_DEVICE_NAME, null),
        permissionIntroDone = sp.getBoolean(K_PERMISSION_INTRO, false),
        trackingDesired = sp.getBoolean(K_TRACKING_DESIRED, false),
        trackingPausedByUser = sp.getBoolean(K_TRACKING_PAUSED, false),
        trackingMode = TrackingMode.fromName(sp.getString(K_TRACKING_MODE, null))?.takeIf { it != TrackingMode.OFF }
            ?: TrackingMode.DEFAULT,
        liveStartedAtMs = sp.longOrNull(K_LIVE_STARTED),
        trackingProblem = sp.getString(K_TRACKING_PROBLEM, null)
            ?.let { name -> TrackingProblem.entries.firstOrNull { it.name == name } },
        lastFixAtMs = sp.longOrNull(K_LAST_FIX),
        lastUploadAtMs = sp.longOrNull(K_UPLOAD_AT),
        lastUploadOk = if (sp.contains(K_UPLOAD_OK)) sp.getBoolean(K_UPLOAD_OK, false) else null,
        lastUploadError = sp.getString(K_UPLOAD_ERROR, null),
        lastUploadCount = sp.getInt(K_UPLOAD_COUNT, 0),
        shareBattery = sp.getBoolean(K_SHARE_BATTERY, false),
        selectedFamilyId = sp.getString(K_SELECTED_FAMILY, null),
    )

    private fun SharedPreferences.longOrNull(key: String): Long? = if (contains(key)) getLong(key, 0) else null

    private companion object {
        const val FILE = "fc_prefs"
        const val K_SERVER_URL = "server_url"
        const val K_SERVER_CLEARTEXT = "server_cleartext"
        const val K_SERVER_VERSION = "server_version"
        const val K_MAP_STYLE = "map_style_url"
        const val K_USER_ID = "user_id"
        const val K_USER_EMAIL = "user_email"
        const val K_USER_NAME = "user_name"
        const val K_DEVICE_ID = "device_id"
        const val K_DEVICE_NAME = "device_name"
        const val K_PERMISSION_INTRO = "permission_intro_done"
        const val K_TRACKING_DESIRED = "tracking_desired"
        const val K_TRACKING_PAUSED = "tracking_paused"
        const val K_TRACKING_MODE = "tracking_mode"
        const val K_LIVE_STARTED = "live_started_at"
        const val K_TRACKING_PROBLEM = "tracking_problem"
        const val K_LAST_FIX = "last_fix_at"
        const val K_UPLOAD_AT = "last_upload_at"
        const val K_UPLOAD_OK = "last_upload_ok"
        const val K_UPLOAD_ERROR = "last_upload_error"
        const val K_UPLOAD_COUNT = "last_upload_count"
        const val K_SHARE_BATTERY = "share_battery"
        const val K_SELECTED_FAMILY = "selected_family"

        val ACCOUNT_KEYS = listOf(
            K_USER_ID, K_USER_EMAIL, K_USER_NAME, K_DEVICE_ID, K_TRACKING_DESIRED, K_TRACKING_PAUSED,
            K_TRACKING_MODE, K_LIVE_STARTED, K_TRACKING_PROBLEM, K_LAST_FIX, K_UPLOAD_AT, K_UPLOAD_OK,
            K_UPLOAD_ERROR, K_UPLOAD_COUNT, K_SHARE_BATTERY, K_SELECTED_FAMILY,
        )
    }
}
