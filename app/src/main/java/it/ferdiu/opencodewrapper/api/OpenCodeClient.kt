package it.ferdiu.opencodewrapper.api

import kotlinx.coroutines.flow.Flow

/**
 * Everything the rest of the app needs from an OpenCode server, kept behind
 * this interface on purpose: OpenCode is mid-migration from its V1 API to a
 * V2 API (confirmed against v1.18.18 - see [OpenCodeClientV2] for the exact
 * endpoints and the assumptions we had to make), and the server-side surface
 * is expected to keep shifting release to release. If it changes again,
 * only the implementation below should need to change - nothing in
 * `service/` or `ui/` should know or care about raw endpoint paths.
 */
interface OpenCodeClient {

    /**
     * Opens the live event stream. Emits [OcEnvelope]s (event + originating
     * project directory) until cancelled or the connection drops (in which
     * case the flow completes/throws and the caller -
     * [it.ferdiu.opencodewrapper.service.OpenCodeEventService] - is
     * responsible for reconnect/backoff).
     */
    fun events(): Flow<OcEnvelope>

    /** Simple reachability/auth check, used before starting the foreground service. */
    suspend fun healthCheck(): Boolean

    /**
     * Best-effort reconnect recovery: fetches the current status of a
     * session so the service can tell whether it's idle/busy/errored even
     * if it missed the event that would normally have announced that.
     *
     * This is intentionally *not* a full conversation replay - the app does
     * not keep a local copy of session content, per the design brief. It is
     * only enough state to decide whether a "session finished" or "session
     * errored" notification was missed while disconnected.
     */
    suspend fun getSessionSnapshot(sessionId: String): SessionSnapshot?

    /** All sessions currently known to the server, for the "which sessions
     *  are active" picture used on reconnect when no specific session is
     *  being tracked yet. */
    suspend fun listActiveSessions(): List<SessionSnapshot>

    /**
     * Replies to a pending permission request. Returns true when the server
     * accepted the reply (2xx). Used by the notification action receiver so
     * the user can answer permission prompts without opening the app.
     */
    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: PermissionDecision,
    ): Boolean

    /**
     * Submits a free-form answer to a pending question. Returns true on 2xx.
     * The answer is the user's whole reply text (typed inline or dictated on
     * Android Auto) - no parsing involved.
     */
    suspend fun replyQuestion(
        sessionId: String,
        requestId: String,
        answer: String,
    ): Boolean
}

data class SessionSnapshot(
    val id: String,
    val title: String?,
    val statusType: String, // "idle" | "busy" | "retry" | "unknown"
)
