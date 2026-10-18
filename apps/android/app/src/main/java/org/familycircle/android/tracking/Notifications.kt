package org.familycircle.android.tracking

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.familycircle.android.MainActivity
import org.familycircle.android.R
import java.text.DateFormat
import java.util.Date

object Notifications {
    const val CHANNEL_TRACKING = "tracking"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_TRACKING = 1
    const val ID_RESUME = 2

    fun ensureChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_TRACKING, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notif_channel_tracking))
                .setDescription(context.getString(R.string.notif_channel_tracking_desc))
                .setShowBadge(false)
                .build(),
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notif_channel_alerts))
                .setDescription(context.getString(R.string.notif_channel_alerts_desc))
                .build(),
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Persistent notification of the location service: mode + time of the last fix, "Pause" action. */
    fun tracking(context: Context, mode: TrackingMode, lastFixAtMs: Long?): Notification {
        val modeLabel = context.getString(modeLabelRes(mode))
        val text = if (lastFixAtMs != null) {
            context.getString(
                R.string.notif_tracking_last_fix,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastFixAtMs)),
            )
        } else {
            context.getString(R.string.notif_tracking_waiting)
        }
        val pause = PendingIntent.getService(
            context,
            1,
            Intent(context, LocationService::class.java).setAction(LocationService.ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(context.getString(R.string.notif_tracking_title, modeLabel))
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notif_action_pause), pause)
            .build()
    }

    /** Shown after a reboot when sharing cannot resume automatically. */
    fun showResumeHint(context: Context) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_resume_title))
            .setContentText(context.getString(R.string.notif_resume_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_resume_text)))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // checked in canPost()
        NotificationManagerCompat.from(context).notify(ID_RESUME, notification)
    }

    fun cancelResumeHint(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_RESUME)
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun modeLabelRes(mode: TrackingMode): Int = when (mode) {
        TrackingMode.OFF -> R.string.mode_off
        TrackingMode.SAVER -> R.string.mode_saver
        TrackingMode.BALANCED -> R.string.mode_balanced
        TrackingMode.LIVE -> R.string.mode_live
    }
}
