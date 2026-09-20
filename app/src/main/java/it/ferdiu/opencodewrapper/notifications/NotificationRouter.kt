package it.ferdiu.opencodewrapper.notifications

import android.content.Context
import android.util.Log
import it.ferdiu.opencodewrapper.api.OcEvent

/**
 * Turns live events into (at most) one Android notification each, per the
 * brief: "avoid notifying for every low-level streaming event or message
 * fragment." Message/part/lsp/pty/file events never reach here - see how
 * [it.ferdiu.opencodewrapper.service.OpenCodeEventService] filters before calling
 * this router.
 *
 * Tracks per-session "was busy" state in memory only, purely to decide
 * whether an idle transition is worth surfacing (finishing a turn) versus
 * noise (a session that was already idle staying idle). This is not
 * persisted - it's fine if it resets on process death, since a reconnect
 * re-derives current status from the server anyway.
 */
class NotificationRouter(private val context: Context) {

    private val sessionWasBusy = mutableMapOf<String, Boolean>()
    private val sessionTitles = mutableMapOf<String, String>()

    /** Project directory per session, learned from the /global/event
     *  envelope - needed to build web-UI deep links
     *  (/{base64url(directory)}/session/{id}). */
    private val sessionDirectories = mutableMapOf<String, String>()

    private val retryStreaks = RetryStreakTracker()
    /** Reverse indexes so session.deleted can cancel prompt notifications
     *  whose stable IDs derive from permissionId/requestId, not sessionId.
     *  In-memory only, like the other router maps. */
    private val sessionPermissions = mutableMapOf<String, MutableSet<String>>()
    private val sessionQuestions = mutableMapOf<String, MutableSet<String>>()

    private fun rememberDirectory(sessionId: String?, directory: String?) {
        if (sessionId != null && directory != null) sessionDirectories[sessionId] = directory
    }

    fun onEvent(event: OcEvent, directory: String? = null) {
        when (event) {
            is OcEvent.SessionUpdated -> {
                rememberDirectory(event.sessionId, directory)
                event.title?.let { sessionTitles[event.sessionId] = it }
            }

            is OcEvent.SessionBusy -> {
                rememberDirectory(event.sessionId, directory)
                retryStreaks.reset(event.sessionId)
                sessionWasBusy[event.sessionId] = true
            }

            is OcEvent.SessionIdle -> {
                rememberDirectory(event.sessionId, directory)
                retryStreaks.reset(event.sessionId)
                val wasBusy = sessionWasBusy[event.sessionId] == true
                sessionWasBusy[event.sessionId] = false
                if (wasBusy) {
                    NotificationHelper.notifyEvent(
                        context = context,
                        notificationId = NotificationHelper.statusNotificationId(event.sessionId),
                        channel = NotificationHelper.CHANNEL_STATUS,
                        title = "Session finished",
                        text = sessionLabel(event.sessionId),
                        sessionId = event.sessionId,
                        directory = sessionDirectories[event.sessionId],
                    )
                }
            }

            is OcEvent.SessionRetrying -> {
                rememberDirectory(event.sessionId, directory)
                if (retryStreaks.shouldNotify(event.sessionId, event.attempt)) {
                    NotificationHelper.notifyEvent(
                        context = context,
                        notificationId = NotificationHelper.errorNotificationId(event.sessionId),
                        channel = NotificationHelper.CHANNEL_ERROR,
                        title = "Session struggling",
                        text = "${sessionLabel(event.sessionId)} is stuck retrying (attempt ${event.attempt}) — check the server or model",
                        sessionId = event.sessionId,
                        directory = sessionDirectories[event.sessionId],
                    )
                } else {
                    Log.d(TAG, "Session ${event.sessionId} retrying (attempt ${event.attempt})")
                }
            }

            is OcEvent.SessionError -> {
                rememberDirectory(event.sessionId, directory)
                event.sessionId?.let { retryStreaks.reset(it) }
                NotificationHelper.notifyEvent(
                    context = context,
                    notificationId = NotificationHelper.errorNotificationId(event.sessionId ?: "unknown"),
                    channel = NotificationHelper.CHANNEL_ERROR,
                    title = "Session error",
                    text = event.message?.takeIf { it.isNotBlank() }
                        ?: "Something went wrong in ${sessionLabel(event.sessionId)}",
                    sessionId = event.sessionId,
                    directory = sessionDirectories[event.sessionId],
                )
            }

            is OcEvent.PermissionAsked -> {
                rememberDirectory(event.sessionId, directory)
                sessionPermissions.getOrPut(event.sessionId) { mutableSetOf() }.add(event.permissionId)
                NotificationHelper.notifyPermission(
                    context = context,
                    permissionId = event.permissionId,
                    sessionId = event.sessionId,
                    commandLabel = event.title?.takeIf { it.isNotBlank() }
                        ?: "The agent is waiting on a permission decision",
                    sessionLabel = sessionLabel(event.sessionId),
                    directory = sessionDirectories[event.sessionId],
                )
            }

            is OcEvent.PermissionReplied -> {
                sessionPermissions[event.sessionId]?.remove(event.permissionId)
                NotificationHelper.cancelNotification(
                    context,
                    NotificationHelper.permissionNotificationId(event.permissionId),
                )
            }

            is OcEvent.QuestionAsked -> {
                rememberDirectory(event.sessionId, directory)
                sessionQuestions.getOrPut(event.sessionId) { mutableSetOf() }.add(event.requestId)
                NotificationHelper.notifyQuestion(
                    context = context,
                    requestId = event.requestId,
                    sessionId = event.sessionId,
                    prompt = event.prompt?.takeIf { it.isNotBlank() }
                        ?: "The agent needs your input",
                    sessionLabel = sessionLabel(event.sessionId),
                    directory = sessionDirectories[event.sessionId],
                )
            }

            is OcEvent.QuestionSettled -> {
                sessionQuestions[event.sessionId]?.remove(event.requestId)
                NotificationHelper.cancelNotification(
                    context,
                    NotificationHelper.questionNotificationId(event.requestId),
                )
            }

            is OcEvent.SessionDeleted -> {
                NotificationHelper.cancelNotification(context, NotificationHelper.statusNotificationId(event.sessionId))
                NotificationHelper.cancelNotification(context, NotificationHelper.errorNotificationId(event.sessionId))
                sessionPermissions.remove(event.sessionId)?.forEach {
                    NotificationHelper.cancelNotification(context, NotificationHelper.permissionNotificationId(it))
                }
                sessionQuestions.remove(event.sessionId)?.forEach {
                    NotificationHelper.cancelNotification(context, NotificationHelper.questionNotificationId(it))
                }
                sessionWasBusy.remove(event.sessionId)
                sessionTitles.remove(event.sessionId)
                sessionDirectories.remove(event.sessionId)
                retryStreaks.reset(event.sessionId)
            }

            is OcEvent.Connected, is OcEvent.Heartbeat, is OcEvent.Unknown -> {
                // No user-facing notification for connection bookkeeping or
                // event types we don't specifically model.
            }
        }
    }

    /** Called after a reconnect recovery pass finds a session finished/errored
     *  while we were disconnected, so it still gets surfaced. */
    fun onRecoveredStatus(sessionId: String, statusType: String) {
        when (statusType) {
            "idle" -> {
                // No longer stuck retrying, whether or not we notify.
                retryStreaks.reset(sessionId)
                if (sessionWasBusy[sessionId] == true) {
                    sessionWasBusy[sessionId] = false
                    NotificationHelper.notifyEvent(
                        context = context,
                        notificationId = NotificationHelper.statusNotificationId(sessionId),
                        channel = NotificationHelper.CHANNEL_STATUS,
                        title = "Session finished",
                        text = sessionLabel(sessionId),
                        sessionId = sessionId,
                        directory = sessionDirectories[sessionId],
                    )
                }
            }
            "busy" -> {
                retryStreaks.reset(sessionId)
                sessionWasBusy[sessionId] = true
            }
        }
    }

    fun markKnownBusy(sessionId: String) {
        sessionWasBusy[sessionId] = true
    }

    private fun sessionLabel(sessionId: String?): String =
        sessionId?.let { sessionTitles[it] } ?: "your OpenCode session"

    companion object {
        private const val TAG = "NotificationRouter"
    }
}
