package org.familycircle.android.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.familycircle.android.MainActivity
import org.familycircle.android.R
import org.familycircle.android.api.Alert
import org.familycircle.android.util.Logger

/**
 * Notification channels and notifications for alerts (docs/ALERTS.md, "Verhalten der Android-App").
 * Texts contain names and place names only – never coordinates.
 */
object AlertNotifier {
    const val CHANNEL_SOS = "sos"
    const val CHANNEL_CHECKINS = "checkins"
    const val CHANNEL_PLACES = "places"
    const val CHANNEL_BATTERY = "battery"
    const val CHANNEL_RING = "ring"
    const val CHANNEL_ALERT_SERVICE = "alert_service"

    const val ID_ALERT_SERVICE = 3
    private const val ID_SOS = 100
    const val ID_RING = 101
    private const val ID_INFO = 102

    /** Opens the map focused on a person (and their SOS position). */
    const val ACTION_SHOW_ON_MAP = "org.familycircle.android.action.SHOW_ON_MAP"
    const val EXTRA_USER_ID = "user_id"
    const val EXTRA_SOS = "sos"

    /** Tap on the ringing notification. */
    const val ACTION_STOP_RING_AND_OPEN = "org.familycircle.android.action.STOP_RING_AND_OPEN"

    private val VIBRATION_SOS = longArrayOf(0, 800, 400, 800, 400, 800)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sos = NotificationChannel(CHANNEL_SOS, context.getString(R.string.alert_channel_sos), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.alert_channel_sos_desc)
            setSound(alarmSound, alarmAttributes)
            enableVibration(true)
            vibrationPattern = VIBRATION_SOS
            enableLights(true)
            // Honoured only if the user allows it for this channel; harmless otherwise.
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val ring = NotificationChannel(CHANNEL_RING, context.getString(R.string.alert_channel_ring), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.alert_channel_ring_desc)
            setSound(null, null) // the ringtone is played by Ringer (alarm stream, up to 60 s)
            enableVibration(false)
            setBypassDnd(true)
        }
        val checkIns = NotificationChannel(CHANNEL_CHECKINS, context.getString(R.string.alert_channel_checkins), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.alert_channel_checkins_desc)
        }
        val places = NotificationChannel(CHANNEL_PLACES, context.getString(R.string.alert_channel_places), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.alert_channel_places_desc)
        }
        val battery = NotificationChannel(CHANNEL_BATTERY, context.getString(R.string.alert_channel_battery), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.alert_channel_battery_desc)
        }
        val service = NotificationChannel(CHANNEL_ALERT_SERVICE, context.getString(R.string.alert_channel_service), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.alert_channel_service_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannels(listOf(sos, ring, checkIns, places, battery, service))
    }

    /** Someone else's SOS: loud (alarm stream, repeats until opened), full screen when allowed. */
    fun showSos(context: Context, alert: Alert) {
        val name = alert.actor.displayName
        val open = showOnMapIntent(context, alert.actor.userId, sos = true)
        val text = context.getString(R.string.alert_sos_text, name)
        val builder = base(context, CHANNEL_SOS)
            .setContentTitle(context.getString(R.string.alert_sos_title, name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n" + context.getString(R.string.alert_sos_hint)))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(context, R.color.sos_red))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.alert_action_open_map), open)
        if (canUseFullScreen(context)) builder.setFullScreenIntent(open, true)
        val notification = builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
        post(context, sosTag(alert.actor.userId), ID_SOS, notification)
    }

    /** Replaces (and thereby silences) the person's SOS notification; [notify] shows "… is safe". */
    fun sosEnded(context: Context, alert: Alert, notify: Boolean) {
        val tag = sosTag(alert.actor.userId)
        if (!notify) {
            NotificationManagerCompat.from(context).cancel(tag, ID_SOS)
            return
        }
        val name = alert.actor.displayName
        val notification = base(context, CHANNEL_SOS)
            .setContentTitle(context.getString(R.string.alert_sos_resolved, name))
            .setContentIntent(showOnMapIntent(context, alert.actor.userId, sos = false))
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        post(context, tag, ID_SOS, notification)
    }

    fun showCheckIn(context: Context, alert: Alert) {
        val name = alert.actor.displayName
        val title = alert.place?.name?.let { context.getString(R.string.alert_checkin_at, name, it) }
            ?: context.getString(R.string.alert_checkin, name)
        val builder = base(context, CHANNEL_CHECKINS)
            .setContentTitle(title)
            .setContentIntent(showOnMapIntent(context, alert.actor.userId, sos = false))
            .setAutoCancel(true)
        alert.message?.takeIf { it.isNotBlank() }?.let { builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        post(context, "checkin:${alert.id}", ID_INFO, builder.build())
    }

    fun showPlace(context: Context, alert: Alert, arrived: Boolean) {
        val name = alert.actor.displayName
        val place = alert.place?.name ?: return
        val title = context.getString(if (arrived) R.string.alert_arrival else R.string.alert_departure, name, place)
        val notification = base(context, CHANNEL_PLACES)
            .setContentTitle(title)
            .setContentIntent(showOnMapIntent(context, alert.actor.userId, sos = false))
            .setAutoCancel(true)
            .build()
        post(context, "place:${alert.id}", ID_INFO, notification)
    }

    fun showBattery(context: Context, alert: Alert, shutdown: Boolean) {
        val name = alert.actor.displayName
        val title = if (shutdown) {
            context.getString(R.string.alert_last_location, name)
        } else {
            context.getString(R.string.alert_battery_low, name, alert.batteryPct ?: 0)
        }
        val notification = base(context, CHANNEL_BATTERY)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(showOnMapIntent(context, alert.actor.userId, sos = false))
            .setAutoCancel(true)
            .build()
        post(context, "battery:${alert.actor.userId}", ID_INFO, notification)
    }

    /** Ongoing while the phone rings; "Stopp" and swiping it away both stop the sound. */
    fun ring(context: Context, actorName: String, self: Boolean): Notification {
        val stop = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, AlertActionReceiver::class.java).setAction(AlertActionReceiver.ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (self) context.getString(R.string.alert_ring_self) else context.getString(R.string.alert_ring, actorName)
        return base(context, CHANNEL_RING)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.alert_ring_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // Tapping opens the app, which stops the sound as well (MainActivity).
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    2,
                    Intent(context, MainActivity::class.java).setAction(ACTION_STOP_RING_AND_OPEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setDeleteIntent(stop)
            .addAction(0, context.getString(R.string.alert_action_stop), stop)
            .build()
    }

    /** Persistent notice of [AlertService]. */
    fun service(context: Context): Notification = base(context, CHANNEL_ALERT_SERVICE)
        .setContentTitle(context.getString(R.string.alert_service_title))
        .setContentText(context.getString(R.string.alert_service_text))
        .setContentIntent(openApp(context))
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    @SuppressLint("MissingPermission") // checked in canPost()
    fun postRing(context: Context, notification: Notification) {
        if (canPost(context)) NotificationManagerCompat.from(context).notify(ID_RING, notification)
    }

    fun cancelRing(context: Context) = NotificationManagerCompat.from(context).cancel(ID_RING)

    /** Removes every alert notification (sign-out). */
    fun cancelAll(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.activeNotifications.filter { it.id == ID_SOS || it.id == ID_INFO || it.id == ID_RING }.forEach { nm.cancel(it.tag, it.id) }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android 14+ lets the user revoke full-screen notifications; we never block on it. */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    private fun sosTag(userId: String) = "sos:$userId"

    private fun base(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(ContextCompat.getColor(context, R.color.notification_accent))
        .setWhen(System.currentTimeMillis())
        .setShowWhen(true)

    @SuppressLint("MissingPermission") // checked in canPost()
    private fun post(context: Context, tag: String, id: Int, notification: Notification) {
        if (!canPost(context)) {
            Logger.w(TAG, "notifications not allowed, alert not shown")
            return
        }
        NotificationManagerCompat.from(context).notify(tag, id, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showOnMapIntent(context: Context, userId: String, sos: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        (userId.hashCode() * 2) + if (sos) 1 else 0,
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_SHOW_ON_MAP)
            .putExtra(EXTRA_USER_ID, userId)
            .putExtra(EXTRA_SOS, sos)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val TAG = "AlertNotify"
}
