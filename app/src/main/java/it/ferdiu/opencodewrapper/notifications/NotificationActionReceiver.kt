package it.ferdiu.opencodewrapper.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import it.ferdiu.opencodewrapper.R
import it.ferdiu.opencodewrapper.api.OpenCodeClientV2
import it.ferdiu.opencodewrapper.api.PermissionDecision
import it.ferdiu.opencodewrapper.data.ServerConfig
import it.ferdiu.opencodewrapper.data.ServerConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles answers coming from notification actions: permission button taps
 * ([ACTION_DECISION]), Android Auto permission voice replies
 * ([ACTION_VOICE_DECISION], parsed by [PermissionReplyParser]) and inline
 * question answers ([ACTION_QUESTION_ANSWER], typed or spoken - the text IS
 * the answer). Runs the HTTP call off the main thread via [goAsync]; on any
 * failure re-posts the original notification with an explanatory note so no
 * answer is ever silently lost.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECISION -> {
                val decision = intent.getStringExtra(EXTRA_DECISION)
                    ?.let { runCatching { PermissionDecision.valueOf(it) }.getOrNull() }
                if (decision == null) repostPermission(context, intent, context.getString(R.string.notif_voice_not_understood))
                else sendPermissionReply(context, intent, decision)
            }

            ACTION_VOICE_DECISION -> {
                val transcript = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_VOICE_REPLY)?.toString()
                val decision = PermissionReplyParser.parse(transcript)
                if (decision == null) repostPermission(context, intent, context.getString(R.string.notif_voice_not_understood))
                else sendPermissionReply(context, intent, decision)
            }

            ACTION_QUESTION_ANSWER -> {
                val answer = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_QUESTION_ANSWER)?.toString()?.trim()
                if (answer.isNullOrEmpty()) repostQuestion(context, intent, context.getString(R.string.notif_answer_blank))
                else sendQuestionReply(context, intent, answer)
            }
        }
    }

    private fun sendPermissionReply(context: Context, intent: Intent, decision: PermissionDecision) {
        val permissionId = intent.getStringExtra(EXTRA_SUBJECT_ID) ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, NotificationHelper.permissionNotificationId(permissionId))

        sendReply(context, notificationId, repost = { note ->
            repostPermission(context, intent, note)
        }) { config ->
            OpenCodeClientV2(config).replyPermission(sessionId, permissionId, decision)
        }
    }

    private fun sendQuestionReply(context: Context, intent: Intent, answer: String) {
        val requestId = intent.getStringExtra(EXTRA_SUBJECT_ID) ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, NotificationHelper.questionNotificationId(requestId))

        sendReply(context, notificationId, repost = { note ->
            repostQuestion(context, intent, note)
        }) { config ->
            OpenCodeClientV2(config).replyQuestion(sessionId, requestId, answer)
        }
    }

    /** Shared goAsync + HTTP + cancel/repost plumbing. */
    private fun sendReply(
        context: Context,
        notificationId: Int,
        repost: (note: String) -> Unit,
        call: suspend (config: ServerConfig) -> Boolean,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = ServerConfigStore(context).get()
                val ok = config != null && runCatching { call(config) }.getOrDefault(false)
                if (ok) {
                    NotificationHelper.cancelNotification(context, notificationId)
                } else {
                    repost(
                        if (config == null) context.getString(R.string.notif_no_server)
                        else context.getString(R.string.notif_reply_failed)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reply failed", e)
                repost(context.getString(R.string.notif_reply_failed))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun repostPermission(context: Context, intent: Intent, note: String) {
        NotificationHelper.notifyPermission(
            context = context,
            permissionId = intent.getStringExtra(EXTRA_SUBJECT_ID) ?: return,
            sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return,
            commandLabel = intent.getStringExtra(EXTRA_LABEL) ?: return,
            sessionLabel = intent.getStringExtra(EXTRA_SESSION_LABEL) ?: "",
            directory = intent.getStringExtra(EXTRA_DIRECTORY),
            extraLine = note,
        )
    }

    private fun repostQuestion(context: Context, intent: Intent, note: String) {
        NotificationHelper.notifyQuestion(
            context = context,
            requestId = intent.getStringExtra(EXTRA_SUBJECT_ID) ?: return,
            sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return,
            prompt = intent.getStringExtra(EXTRA_LABEL) ?: return,
            sessionLabel = intent.getStringExtra(EXTRA_SESSION_LABEL) ?: "",
            directory = intent.getStringExtra(EXTRA_DIRECTORY),
            extraLine = note,
        )
    }

    companion object {
        private const val TAG = "NotificationActionReceiver"
        const val ACTION_DECISION = "it.ferdiu.opencodewrapper.action.PERMISSION_DECISION"
        const val ACTION_VOICE_DECISION = "it.ferdiu.opencodewrapper.action.PERMISSION_VOICE_DECISION"
        const val ACTION_QUESTION_ANSWER = "it.ferdiu.opencodewrapper.action.QUESTION_ANSWER"
        const val KEY_VOICE_REPLY = "voice_reply"
        const val KEY_QUESTION_ANSWER = "question_answer"

        private const val EXTRA_SUBJECT_ID = "subject_id"       // permissionId or requestId
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_DECISION = "decision"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_LABEL = "label"                 // command label or question prompt
        private const val EXTRA_SESSION_LABEL = "session_label"
        private const val EXTRA_DIRECTORY = "directory"

        fun decisionIntent(
            context: Context,
            permissionId: String,
            sessionId: String,
            commandLabel: String,
            sessionLabel: String,
            directory: String?,
            decision: PermissionDecision,
            notificationId: Int,
        ): Intent = base(context, ACTION_DECISION, permissionId, sessionId, commandLabel, sessionLabel, directory, notificationId)
            .putExtra(EXTRA_DECISION, decision.name)

        fun voiceReplyIntent(
            context: Context,
            permissionId: String,
            sessionId: String,
            commandLabel: String,
            sessionLabel: String,
            directory: String?,
            notificationId: Int,
        ): Intent = base(context, ACTION_VOICE_DECISION, permissionId, sessionId, commandLabel, sessionLabel, directory, notificationId)

        fun questionAnswerIntent(
            context: Context,
            requestId: String,
            sessionId: String,
            prompt: String,
            sessionLabel: String,
            directory: String?,
            notificationId: Int,
        ): Intent = base(context, ACTION_QUESTION_ANSWER, requestId, sessionId, prompt, sessionLabel, directory, notificationId)

        private fun base(
            context: Context,
            action: String,
            subjectId: String,
            sessionId: String,
            label: String,
            sessionLabel: String,
            directory: String?,
            notificationId: Int,
        ): Intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_SUBJECT_ID, subjectId)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_SESSION_LABEL, sessionLabel)
            .putExtra(EXTRA_DIRECTORY, directory)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }
}
