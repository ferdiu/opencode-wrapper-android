package com.opencode.wrapper.notifications

import android.content.Context
import android.util.Log
import com.opencode.wrapper.api.OcEvent

/**
 * Turns live events into (at most) one Android notification each, per the
 * brief: "avoid notifying for every low-level streaming event or message
 * fragment." Message/part/lsp/pty/file events never reach here - see how
 * [com.opencode.wrapper.service.OpenCodeEventService] filters before calling
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

    fun onEvent(event: OcEvent) {
        when (event) {
            is OcEvent.SessionUpdated -> {
                event.title?.let { sessionTitles[event.sessionId] = it }
            }

            is OcEvent.SessionBusy -> {
                sessionWasBusy[event.sessionId] = true
            }

            is OcEvent.SessionIdle -> {
                val wasBusy = sessionWasBusy[event.sessionId] == true
                sessionWasBusy[event.sessionId] = false
                if (wasBusy) {
                    NotificationHelper.notifyEvent(
                        context = context,
                        channel = NotificationHelper.CHANNEL_STATUS,
                        title = "Session finished",
                        text = sessionLabel(event.sessionId),
                        sessionId = event.sessionId,
                    )
                }
            }

            is OcEvent.SessionRetrying -> {
                // Deliberately silent: transient retries are exactly the kind
                // of low-level noise the brief asks us to skip. A persistent
                // failure will still surface via SessionError.
                Log.d(TAG, "Session ${event.sessionId} retrying (attempt ${event.attempt})")
            }

            is OcEvent.SessionError -> {
                NotificationHelper.notifyEvent(
                    context = context,
                    channel = NotificationHelper.CHANNEL_ERROR,
                    title = "Session error",
                    text = event.message?.takeIf { it.isNotBlank() }
                        ?: "Something went wrong in ${sessionLabel(event.sessionId)}",
                    sessionId = event.sessionId,
                )
            }

            is OcEvent.PermissionAsked -> {
                NotificationHelper.notifyEvent(
                    context = context,
                    channel = NotificationHelper.CHANNEL_ACTION,
                    title = "Permission needed",
                    text = event.title?.takeIf { it.isNotBlank() }
                        ?: "${sessionLabel(event.sessionId)} is waiting on a permission decision",
                    sessionId = event.sessionId,
                )
            }

            is OcEvent.QuestionAsked -> {
                NotificationHelper.notifyEvent(
                    context = context,
                    channel = NotificationHelper.CHANNEL_ACTION,
                    title = "Question from agent",
                    text = event.prompt?.takeIf { it.isNotBlank() }
                        ?: "${sessionLabel(event.sessionId)} needs your input",
                    sessionId = event.sessionId,
                )
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
            "idle" -> if (sessionWasBusy[sessionId] == true) {
                sessionWasBusy[sessionId] = false
                NotificationHelper.notifyEvent(
                    context, NotificationHelper.CHANNEL_STATUS,
                    "Session finished", sessionLabel(sessionId), sessionId,
                )
            }
            "busy" -> sessionWasBusy[sessionId] = true
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
