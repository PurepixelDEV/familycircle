package org.familycircle.android.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.familycircle.android.BuildConfig
import org.familycircle.android.data.prefs.AppPrefs
import org.familycircle.android.live.LiveConnectionState
import org.familycircle.android.tracking.TrackingController
import org.familycircle.android.tracking.TrackingMode
import org.familycircle.android.util.Rfc3339
import kotlin.math.roundToInt

/**
 * Technical state for hardware testers. Deliberately contains **no** coordinates, tokens, e-mail
 * addresses, user/device ids or the server address (only whether it is http or https).
 */
data class DiagnosticsSnapshot(
    val appVersion: String,
    val versionCode: Int,
    val buildType: String,
    val applicationId: String,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val serverScheme: String?,
    val serverVersion: String?,
    val trackingDesired: Boolean,
    val trackingPausedByUser: Boolean,
    val trackingMode: TrackingMode,
    val effectiveMode: TrackingMode?,
    val serviceRunning: Boolean,
    val providersInUse: List<String>,
    val providersAvailable: List<String>,
    val locationEnabled: Boolean,
    val precise: Boolean,
    val approximate: Boolean,
    val background: Boolean,
    val notifications: Boolean,
    val camera: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val queueSize: Int,
    val lastFixAtMs: Long?,
    val lastFixAccuracyM: Double?,
    val lastUploadAtMs: Long?,
    val lastUploadOk: Boolean?,
    val lastUploadError: String?,
    val lastUploadCount: Int,
    val trackingProblem: String?,
    val liveState: LiveConnectionState,
    val liveLastConnectedAtMs: Long?,
    val createdAtMs: Long,
) {
    companion object {
        fun collect(
            context: Context,
            prefs: AppPrefs,
            queueSize: Int,
            running: Boolean,
            effectiveMode: TrackingMode?,
            providersInUse: List<String>,
            liveState: LiveConnectionState,
            liveLastConnectedAtMs: Long?,
            nowMs: Long = System.currentTimeMillis(),
        ): DiagnosticsSnapshot {
            fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            val lm = context.getSystemService(LocationManager::class.java)
            val pm = context.getSystemService(PowerManager::class.java)
            return DiagnosticsSnapshot(
                appVersion = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildType = BuildConfig.BUILD_TYPE,
                applicationId = context.packageName,
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                serverScheme = prefs.serverUrl?.substringBefore("://"),
                serverVersion = prefs.serverVersion,
                trackingDesired = prefs.trackingDesired,
                trackingPausedByUser = prefs.trackingPausedByUser,
                trackingMode = prefs.trackingMode,
                effectiveMode = effectiveMode,
                serviceRunning = running,
                providersInUse = providersInUse,
                providersAvailable = lm?.let { TrackingController.availableProviders(it).sorted() }.orEmpty(),
                locationEnabled = lm?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } == true,
                precise = has(Manifest.permission.ACCESS_FINE_LOCATION),
                approximate = has(Manifest.permission.ACCESS_COARSE_LOCATION),
                background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(Manifest.permission.POST_NOTIFICATIONS),
                camera = has(Manifest.permission.CAMERA),
                ignoringBatteryOptimizations = pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
                queueSize = queueSize,
                lastFixAtMs = prefs.lastFixAtMs,
                lastFixAccuracyM = prefs.lastFixAccuracyM,
                lastUploadAtMs = prefs.lastUploadAtMs,
                lastUploadOk = prefs.lastUploadOk,
                lastUploadError = prefs.lastUploadError,
                lastUploadCount = prefs.lastUploadCount,
                trackingProblem = prefs.trackingProblem?.name,
                liveState = liveState,
                liveLastConnectedAtMs = liveLastConnectedAtMs,
                createdAtMs = nowMs,
            )
        }
    }
}

/** Plain-text report for "Copy diagnostics" (English on purpose: it is read by developers). */
object DiagnosticsReport {
    fun format(s: DiagnosticsSnapshot): String = buildString {
        fun line(label: String, value: String) = append(label).append(": ").append(value).append('\n')
        fun yn(b: Boolean) = if (b) "yes" else "no"
        fun time(ms: Long?) = ms?.let { Rfc3339.format(it) } ?: "-"

        append("FamilyCircle diagnostics\n")
        line("Created", time(s.createdAtMs))
        line("App", "${s.appVersion} (${s.versionCode}), ${s.buildType}, ${s.applicationId}")
        line("Android", "${s.androidRelease} (API ${s.sdkInt})")
        line("Device", "${s.manufacturer} ${s.model}".trim())
        line(
            "Server",
            when (s.serverScheme) {
                null -> "not configured"
                "http" -> "http (unencrypted), version ${s.serverVersion ?: "-"}"
                else -> "${s.serverScheme}, version ${s.serverVersion ?: "-"}"
            },
        )
        val sharing = when {
            s.serviceRunning -> "on"
            s.trackingPausedByUser || s.trackingDesired -> "paused"
            else -> "off"
        }
        line("Sharing", "$sharing, mode ${s.trackingMode.name}" + (s.effectiveMode?.let { ", effective ${it.name}" } ?: ""))
        line("Service running", yn(s.serviceRunning))
        s.trackingProblem?.let { line("Tracking problem", it) }
        line("Location providers in use", s.providersInUse.joinToString().ifEmpty { "-" })
        line("Location providers available", s.providersAvailable.joinToString().ifEmpty { "-" })
        line("Location enabled", yn(s.locationEnabled))
        line(
            "Permissions",
            "fine=${yn(s.precise)}, coarse=${yn(s.approximate)}, background=${yn(s.background)}, " +
                "notifications=${yn(s.notifications)}, camera=${yn(s.camera)}",
        )
        line("Battery optimisation exempt", yn(s.ignoringBatteryOptimizations))
        line("Queue", "${s.queueSize} position(s)")
        line("Last fix", time(s.lastFixAtMs) + (s.lastFixAccuracyM?.let { ", accuracy ${it.roundToInt()} m" } ?: ""))
        line(
            "Last upload",
            when (s.lastUploadOk) {
                null -> "-"
                true -> "${time(s.lastUploadAtMs)} ok (${s.lastUploadCount} sent)"
                false -> "${time(s.lastUploadAtMs)} failed (${s.lastUploadError ?: "unknown"})"
            },
        )
        line("Live updates (WebSocket)", "${s.liveState.name.lowercase()}, last connected ${time(s.liveLastConnectedAtMs)}")
    }
}
