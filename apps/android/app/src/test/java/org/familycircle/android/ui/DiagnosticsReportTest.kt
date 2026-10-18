package org.familycircle.android.ui

import org.familycircle.android.live.LiveConnectionState
import org.familycircle.android.tracking.TrackingMode
import org.familycircle.android.ui.settings.DiagnosticsReport
import org.familycircle.android.ui.settings.DiagnosticsSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    private val snapshot = DiagnosticsSnapshot(
        appVersion = "0.2.0-debug", versionCode = 2, buildType = "debug", applicationId = "org.familycircle.android.debug",
        androidRelease = "16", sdkInt = 36, manufacturer = "Google", model = "Pixel 9",
        serverScheme = "http", serverVersion = "0.2.0",
        trackingDesired = true, trackingPausedByUser = false, trackingMode = TrackingMode.BALANCED, effectiveMode = TrackingMode.BALANCED,
        serviceRunning = true, providersInUse = listOf("fused"), providersAvailable = listOf("fused", "gps", "network", "passive"),
        locationEnabled = true, precise = true, approximate = true, background = false, notifications = true, camera = false,
        ignoringBatteryOptimizations = false, queueSize = 3, lastFixAtMs = 1_760_000_000_000, lastFixAccuracyM = 12.4,
        lastUploadAtMs = 1_760_000_005_000, lastUploadOk = false, lastUploadError = "network", lastUploadCount = 0,
        trackingProblem = null, liveState = LiveConnectionState.CONNECTED, liveLastConnectedAtMs = 1_760_000_000_000,
        createdAtMs = 1_760_000_100_000,
    )

    @Test
    fun `report contains the tester-relevant facts`() {
        val text = DiagnosticsReport.format(snapshot)
        listOf(
            "0.2.0-debug (2), debug", "Android: 16 (API 36)", "Google Pixel 9", "http (unencrypted)", "Service running: yes",
            "in use: fused", "fine=yes", "background=no", "camera=no", "Battery optimisation exempt: no", "Queue: 3",
            "accuracy 12 m", "failed (network)", "connected",
        ).forEach { assertTrue("missing '$it' in\n$text", text.contains(it)) }
    }

    @Test
    fun `report has no coordinates, tokens, e-mail or server address`() {
        val text = DiagnosticsReport.format(snapshot)
        assertFalse(text.contains("@"))
        assertFalse(text.contains("Bearer"))
        assertFalse(text.contains("://"))
        // No decimal numbers that could be coordinates (timestamps have ":ss.SSS", versions short parts).
        assertFalse(Regex("""(?<![:\d])-?\d{1,3}\.\d{4,}""").containsMatchIn(text))
    }
}
