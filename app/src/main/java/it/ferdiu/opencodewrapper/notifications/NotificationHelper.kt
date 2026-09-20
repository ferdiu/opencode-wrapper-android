package it.ferdiu.opencodewrapper.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import it.ferdiu.opencodewrapper.R
import it.ferdiu.opencodewrapper.api.PermissionDecision
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

    // Stable per-subject notification IDs (base + 15-bit hash of the subject
    // key) so follow-up events for the same subject replace rather than stack
    // notifications, and cancellation can target them later. Bases are spaced
    // more than 0x8000 apart so the hash ranges of the four kinds never
    // overlap and a cross-kind collision can't cancel the wrong notification.
    private const val STATUS_ID_BASE = 10_000
    private const val ERROR_ID_BASE = 50_000
    private const val PERMISSION_ID_BASE = 90_000
    private const val QUESTION_ID_BASE = 130_000

    private fun stableId(base: Int, key: String): Int = base + (key.hashCode() and 0x7FFF)

    fun statusNotificationId(sessionId: String): Int = stableId(STATUS_ID_BASE, sessionId)
    fun errorNotificationId(sessionId: String): Int = stableId(ERROR_ID_BASE, sessionId)
    fun permissionNotificationId(permissionId: String): Int = stableId(PERMISSION_ID_BASE, permissionId)
    fun questionNotificationId(requestId: String): Int = stableId(QUESTION_ID_BASE, requestId)

    fun cancelNotification(context: Context, notificationId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId)
    }

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

    /** Emits a user-facing notification for a session event. Tapping it opens
     *  the app deep-linked into that session in the WebView when the project
     *  directory is known (web-UI route /{base64url(directory)}/session/{id});
     *  otherwise it just opens the app. */
    fun notifyEvent(
        context: Context,
        notificationId: Int,
        channel: String,
        title: String,
        text: String,
        sessionId: String?,
        directory: String? = null,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return

        val pendingIntent = if (sessionId != null && directory != null) {
            sessionContentIntent(context, sessionId, directory)
        } else {
            PendingIntent.getActivity(
                context,
                (sessionId ?: title).hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

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
            .extend(carExtender(speaker = title, message = "$title: $text", readIntent = pendingIntent))
            .build()

        manager.notify(notificationId, notification)
    }

    /** Makes a notification visible to Android Auto with a read-aloud ("Play")
     *  affordance. Read-only notifications (status/error) pass no reply action;
     *  permission/question notifications pass one so Auto offers a voice reply. */
    @Suppress("DEPRECATION") // CarExtender is the only pre-API 33 Auto messaging API.
    private fun carExtender(
        speaker: String,
        message: String,
        readIntent: PendingIntent,
        replyAction: NotificationCompat.Action? = null,
    ): NotificationCompat.CarExtender {
        val conversation = NotificationCompat.CarExtender.UnreadConversation.Builder(speaker)
            .addMessage(message)
            .setLatestTimestamp(System.currentTimeMillis())
            .setReadPendingIntent(readIntent)
        if (replyAction != null) {
            val remoteInput = replyAction.remoteInputs?.firstOrNull()
            if (remoteInput != null) {
                conversation.setReplyAction(replyAction.actionIntent, remoteInput)
            }
        }
        return NotificationCompat.CarExtender().setUnreadConversation(conversation.build())
    }

    /** Shared content intent: deep-links into the session when the project
     *  directory is known, otherwise just opens the app. */
    private fun sessionContentIntent(context: Context, sessionId: String, directory: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            if (directory != null) putExtra(MainActivity.EXTRA_DIRECTORY, directory)
        }
        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun notifyPermission(
        context: Context,
        permissionId: String,
        sessionId: String,
        commandLabel: String,
        sessionLabel: String,
        directory: String?,
        extraLine: String? = null,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return

        val notificationId = permissionNotificationId(permissionId)

        // Notification IDs are 15-bit hashes, so two colliding subjects share
        // one notification ID (they degrade to a single shared notification,
        // which is acceptable) - but request codes must fold in the FULL
        // subject hash so FLAG_UPDATE_CURRENT never overwrites one
        // notification's action extras with another's.
        fun decisionPendingIntent(decision: PermissionDecision, slot: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId * 8 + slot + permissionId.hashCode(),
                NotificationActionReceiver.decisionIntent(
                    context, permissionId, sessionId, commandLabel, sessionLabel, directory, decision, notificationId,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val contentIntent = sessionContentIntent(context, sessionId, directory)

        // Voice reply for Android Auto (mutable: the system fills in the RemoteInput).
        val voiceRemoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_VOICE_REPLY)
            .setLabel(context.getString(R.string.notif_voice_reply_prompt))
            .build()
        val voiceIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 8 + permissionId.hashCode(),
            NotificationActionReceiver.voiceReplyIntent(
                context, permissionId, sessionId, commandLabel, sessionLabel, directory, notificationId,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val voiceAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.notif_voice_reply_prompt),
            voiceIntent,
        ).addRemoteInput(voiceRemoteInput).build()

        val text = buildString {
            append(commandLabel)
            if (extraLine != null) append("\n").append(extraLine)
        }
        val carMessage = buildString {
            append("Permission needed: ").append(commandLabel)
            append(". ").append(context.getString(R.string.notif_permission_voice_hint))
            if (extraLine != null) append(' ').append(extraLine)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ACTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Permission needed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(sessionLabel)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_notification, context.getString(R.string.notif_action_allow), decisionPendingIntent(PermissionDecision.ONCE, 1))
            .addAction(R.drawable.ic_notification, context.getString(R.string.notif_action_allow_always), decisionPendingIntent(PermissionDecision.ALWAYS, 2))
            .addAction(R.drawable.ic_notification, context.getString(R.string.notif_action_deny), decisionPendingIntent(PermissionDecision.REJECT, 3))
            .extend(carExtender(sessionLabel, carMessage, contentIntent, voiceAction))
            .build()

        manager.notify(notificationId, notification)
    }

    fun notifyQuestion(
        context: Context,
        requestId: String,
        sessionId: String,
        prompt: String,
        sessionLabel: String,
        directory: String?,
        extraLine: String? = null,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return

        val notificationId = questionNotificationId(requestId)
        val contentIntent = sessionContentIntent(context, sessionId, directory)

        val answerRemoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_QUESTION_ANSWER)
            .setLabel(context.getString(R.string.notif_question_reply_prompt))
            .build()
        val answerIntent = PendingIntent.getBroadcast(
            context,
            notificationId + requestId.hashCode(),
            NotificationActionReceiver.questionAnswerIntent(
                context, requestId, sessionId, prompt, sessionLabel, directory, notificationId,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val answerAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.notif_action_answer),
            answerIntent,
        ).addRemoteInput(answerRemoteInput).build()

        val text = buildString {
            append(prompt)
            if (extraLine != null) append("\n").append(extraLine)
        }
        val carMessage = buildString {
            append("Question from ").append(sessionLabel).append(": ").append(prompt)
            if (extraLine != null) append(' ').append(extraLine)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ACTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Question from agent")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(sessionLabel)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(answerAction)
            .extend(carExtender(sessionLabel, carMessage, contentIntent, answerAction))
            .build()

        manager.notify(notificationId, notification)
    }
}
