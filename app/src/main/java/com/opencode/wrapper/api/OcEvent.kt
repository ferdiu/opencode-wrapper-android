package com.opencode.wrapper.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Parsed representation of an OpenCode SSE event, trimmed down to what the
 * notification layer actually needs.
 *
 * NOTE on schema stability: the confirmed v1.18.18 event union (seen in
 * packages/sdk/js/src/gen/types.gen.ts) uses `permission.updated` /
 * `permission.replied` and does not expose an explicit `question.asked`
 * member; the v2 SDK's unified Event type (packages/sdk/js/src/v2/gen/types.gen.ts,
 * referenced from the project's own docs) additionally lists
 * `permission.asked` and `question.asked`. Rather than hard-committing to one
 * naming scheme, [OcEventParser] recognizes both so the app keeps working as
 * the server migrates between them. Anything not recognized becomes [Unknown]
 * instead of crashing the stream - this is the "small abstraction" boundary
 * called out in the project brief.
 */
sealed class OcEvent {
    data class Connected(val raw: JsonObject) : OcEvent()
    data class Heartbeat(val raw: JsonObject) : OcEvent()

    data class SessionIdle(val sessionId: String) : OcEvent()
    data class SessionBusy(val sessionId: String) : OcEvent()
    data class SessionRetrying(val sessionId: String, val attempt: Int, val message: String?) : OcEvent()

    data class SessionError(val sessionId: String?, val message: String?) : OcEvent()

    data class PermissionAsked(
        val permissionId: String,
        val sessionId: String,
        val title: String?,
    ) : OcEvent()

    data class QuestionAsked(
        val requestId: String,
        val sessionId: String,
        val prompt: String?,
    ) : OcEvent()

    data class SessionUpdated(val sessionId: String, val title: String?) : OcEvent()

    /** Anything we don't have a specific case for - kept so future OpenCode
     *  versions don't silently break the connection, just the notification
     *  for that particular event type. */
    data class Unknown(val type: String, val raw: JsonObject) : OcEvent()
}

object OcEventParser {

    /**
     * Parses one SSE `data:` payload. Returns null (and logs upstream) if the
     * payload isn't valid JSON or has no `type` field - we skip rather than
     * fail the whole connection on one malformed event.
     */
    fun parse(json: JsonObject): OcEvent {
        val type = (json["type"] as? JsonPrimitive)?.contentOrNullSafe() ?: return OcEvent.Unknown("unknown", json)
        val props = (json["properties"] as? JsonObject) ?: JsonObject(emptyMap())

        return when (type) {
            "server.connected" -> OcEvent.Connected(json)
            "server.heartbeat" -> OcEvent.Heartbeat(json)

            "session.idle" -> {
                val sid = props.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                OcEvent.SessionIdle(sid)
            }

            "session.status" -> {
                val sid = props.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                val status = props["status"]?.jsonObject
                when (status?.stringOrNull("type")) {
                    "idle" -> OcEvent.SessionIdle(sid)
                    "busy" -> OcEvent.SessionBusy(sid)
                    "retry" -> OcEvent.SessionRetrying(
                        sessionId = sid,
                        attempt = status.intOrNull("attempt") ?: 0,
                        message = status.stringOrNull("message"),
                    )
                    else -> OcEvent.Unknown(type, json)
                }
            }

            "session.error" -> {
                val sid = props.stringOrNull("sessionID")
                val message = props["error"]?.jsonObject?.let { err ->
                    err["data"]?.jsonObject?.stringOrNull("message")
                }
                OcEvent.SessionError(sid, message)
            }

            // Confirmed v1 naming: a permission request shows up as
            // permission.updated with no matching permission.replied yet.
            "permission.updated", "permission.asked" -> {
                val id = props.stringOrNull("id") ?: props.stringOrNull("permissionID")
                val sid = props.stringOrNull("sessionID")
                if (id == null || sid == null) return OcEvent.Unknown(type, json)
                OcEvent.PermissionAsked(id, sid, props.stringOrNull("title"))
            }

            "question.asked" -> {
                val id = props.stringOrNull("requestID") ?: return OcEvent.Unknown(type, json)
                val sid = props.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                OcEvent.QuestionAsked(id, sid, props.stringOrNull("prompt") ?: props.stringOrNull("message"))
            }

            "session.updated", "session.created" -> {
                val info = props["info"]?.jsonObject ?: return OcEvent.Unknown(type, json)
                val sid = info.stringOrNull("id") ?: return OcEvent.Unknown(type, json)
                OcEvent.SessionUpdated(sid, info.stringOrNull("title"))
            }

            else -> OcEvent.Unknown(type, json)
        }
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNullSafe()

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { runCatching { it.content.toInt() }.getOrNull() }
