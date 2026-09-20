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
     *
     * [directory] scopes the call to the project instance that raised the
     * request - required on multi-project servers (verified against live
     * v1.18.31: without it the reply 404s with PermissionNotFoundError when
     * the request belongs to a non-default instance).
     */
    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: PermissionDecision,
        directory: String?,
    ): Boolean

    /**
     * Submits a free-form answer to a pending question. Returns true on 2xx.
     * The answer is the user's whole reply text (typed inline or dictated on
     * Android Auto) - no parsing involved.
     *
     * [directory] scopes the call to the project instance that raised the
     * request (same multi-project reason as [replyPermission]).
     */
    suspend fun replyQuestion(
        sessionId: String,
        requestId: String,
        answer: String,
        directory: String?,
    ): Boolean

    /** All projects known to the server (bare-array shape, field "worktree").
     *  Throws [java.io.IOException] on transport failure. */
    suspend fun listProjects(): List<OcProject>

    /** Newest sessions of ONE project instance ([directory] = project worktree,
     *  required - unscoped calls only see the server's default instance).
     *  Throws [java.io.IOException] on transport failure so car screens can
     *  distinguish "server down" from "no sessions". */
    suspend fun listSessions(limit: Int = 50, directory: String? = null): List<OcSession>

    /** The session's last readable message (see [MessageTextExtractor]), or
     *  null if none exists. [directory] scopes to the right instance.
     *  Throws [java.io.IOException] on transport failure. */
    suspend fun getLastMessage(sessionId: String, directory: String?): SessionMessage?

    /** Admits a user text message to the session. True on 2xx.
     *  Verified live: POST /session/{id}/message {parts:[...]}.
     *  [directory] scopes to the right instance. */
    suspend fun sendPrompt(sessionId: String, text: String, directory: String?): Boolean

    /** Currently pending permission requests for a session, oldest first.
     *  [directory] scopes to the session's project instance (verified live:
     *  unscoped calls only see the default instance). Empty on any failure -
     *  the readout screen simply shows the message variant. */
    suspend fun listPendingPermissions(sessionId: String, directory: String?): List<PendingPermission>
}

data class SessionSnapshot(
    val id: String,
    val title: String?,
    val statusType: String, // "idle" | "busy" | "retry" | "unknown"
)

data class OcProject(
    val id: String,
    /** Project worktree path; instance-scoping key for every session call. */
    val worktree: String,
    /** Display label: worktree basename ("Global" for the "/" worktree). */
    val label: String,
)

data class OcSession(
    val id: String,
    val title: String?,
    val projectLabel: String?,
    /** Full project directory; grouping key for the Projects view and the
     *  instance-scoping key for message/prompt/permission calls. */
    val directory: String?,
    /** time.updated epoch millis; 0 when absent. Used for newest-first sort. */
    val updatedAt: Long = 0,
)

data class PendingPermission(
    val id: String,
    /** Human-readable label: "permission: firstPattern", or just the permission name. */
    val label: String,
)
