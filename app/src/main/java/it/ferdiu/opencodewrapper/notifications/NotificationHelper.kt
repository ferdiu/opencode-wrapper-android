package it.ferdiu.opencodewrapper.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import it.ferdiu.opencodewrapper.R
import it.ferdiu.opencodewrapper.ui.MainActivity

object NotificationHelper {

    // "_v2" suffix: channel importance is immutable once a channel exists on
    // a device, so the importance bump (STATUS: DEFAULT -> HIGH) only takes
    // effect on fresh channel IDs. Legacy channels are deleted below.
    const val CHANNEL_STATUS = "opencode_status_v2"
    const val CHANNEL_ACTION = "opencode_action_v2"
    const val CHANNEL_ERROR = "opencode_error_v2"
    const val CHANNEL_SERVICE = "opencode_service_v2"

    private val LEGACY_CHANNELS = listOf(
        "opencode_status", "opencode_action", "opencode_error", "opencode_service",
    )

    const val SERVICE_NOTIFICATION_ID = 1
    private var nextEventNotificationId = 1000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        LEGACY_CHANNELS.forEach { manager.deleteNotificationChannel(it) }

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.notif_channel_status_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.notif_channel_status_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTION, context.getString(R.string.notif_channel_action_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.notif_channel_action_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ERROR, context.getString(R.string.notif_channel_error_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.notif_channel_error_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.notif_channel_service_name), NotificationManager.IMPORTANCE_MIN)
                .apply { description = context.getString(R.string.notif_channel_service_desc) }
        )
    }

    fun buildServiceNotification(context: Context, contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.service_notif_title))
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** Emits a user-facing notification for a session event, deep-linking back
     *  into the WebView at the relevant session. */
    fun notifyEvent(
        context: Context,
        channel: String,
        title: String,
        text: String,
        sessionId: String?,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (sessionId ?: title).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(
                if (channel == CHANNEL_SERVICE) NotificationCompat.PRIORITY_MIN
                else NotificationCompat.PRIORITY_HIGH
            )
            .build()

        manager.notify(nextEventNotificationId++, notification)
    }
}
