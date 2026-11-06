package it.ferdiu.opencodewrapper.api

import kotlinx.serialization.json.Json
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

    data class PermissionReplied(
        val permissionId: String,
        val sessionId: String,
    ) : OcEvent()

    /** The question was answered or dismissed (possibly on another client) -
     *  either way the notification should come down. */
    data class QuestionSettled(
        val requestId: String,
        val sessionId: String,
    ) : OcEvent()

    data class SessionDeleted(val sessionId: String) : OcEvent()

    data class SessionUpdated(val sessionId: String, val title: String?) : OcEvent()

    /** Anything we don't have a specific case for - kept so future OpenCode
     *  versions don't silently break the connection, just the notification
     *  for that particular event type. */
    data class Unknown(val type: String, val raw: JsonObject) : OcEvent()
}

/** A parsed event plus the project directory it belongs to. The directory
 *  comes from the v1 /global/event envelope ({ "directory": "...", "payload":
 *  {...} }) or the v2 event's location.directory, and is needed to build
 *  web-UI deep links (see ApiVersionDetector.webUiSessionUrl). Null for
 *  events that carry no directory (or older unwrapped payloads). */
data class OcEnvelope(val directory: String?, val event: OcEvent)

object OcEventParser {

    /**
     * Parses one SSE `data:` payload. Returns null (and logs upstream) if the
     * payload isn't valid JSON or has no `type` field - we skip rather than
     * fail the whole connection on one malformed event.
     */
    fun parse(json: JsonObject): OcEvent {
        // /global/event wraps each event in an envelope:
        //   { "directory": "...", "payload": { "id", "type", "properties" } }
        // Unwrap it; instance-scoped /event payloads arrive unwrapped.
        val event = (json["payload"] as? JsonObject)?.takeIf { it["type"] is JsonPrimitive } ?: json

        val type = (event["type"] as? JsonPrimitive)?.contentOrNullSafe() ?: return OcEvent.Unknown("unknown", json)
        val props = (event["properties"] as? JsonObject) ?: JsonObject(emptyMap())

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

            // v2 schema: properties = { id, sessionID, permission, patterns,
            // metadata, always, tool? }. There is no "title" field - use the
            // permission name plus the first pattern as human-readable text.
            // "permission.updated" kept for older servers.
            "permission.updated", "permission.asked" -> {
                val id = props.stringOrNull("id") ?: props.stringOrNull("permissionID")
                val sid = props.stringOrNull("sessionID")
                if (id == null || sid == null) return OcEvent.Unknown(type, json)
                val label = props.stringOrNull("title")
                    ?: props.stringOrNull("permission")?.let { perm ->
                        val pattern = (props["patterns"] as? kotlinx.serialization.json.JsonArray)
                            ?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNullSafe() }
                        if (pattern != null) "$perm: $pattern" else perm
                    }
                OcEvent.PermissionAsked(id, sid, label)
            }

            // v2 schema: properties = { id, sessionID, questions: [{ question,
            // header, options, ... }], tool? }. Older payloads used requestID /
            // prompt - accept both. There is no top-level "prompt" in v2.
            "question.asked" -> {
                val id = props.stringOrNull("requestID") ?: props.stringOrNull("id")
                    ?: return OcEvent.Unknown(type, json)
                val sid = props.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                val prompt = props.stringOrNull("prompt") ?: props.stringOrNull("message")
                    ?: (props["questions"] as? kotlinx.serialization.json.JsonArray)
                        ?.firstOrNull()?.let { q ->
                            (q as? JsonObject)?.stringOrNull("question")
                                ?: (q as? JsonObject)?.stringOrNull("header")
                        }
                OcEvent.QuestionAsked(id, sid, prompt)
            }

            // Verified: properties = { sessionID, requestID, reply }.
            "permission.replied" -> {
                val id = props.stringOrNull("requestID") ?: props.stringOrNull("permissionID") ?: props.stringOrNull("id")
                val sid = props.stringOrNull("sessionID")
                if (id == null || sid == null) return OcEvent.Unknown(type, json)
                OcEvent.PermissionReplied(id, sid)
            }

            // Verified: properties = { sessionID, requestID, answers? }.
            "question.replied", "question.rejected" -> {
                val id = props.stringOrNull("requestID") ?: props.stringOrNull("id")
                val sid = props.stringOrNull("sessionID")
                if (id == null || sid == null) return OcEvent.Unknown(type, json)
                OcEvent.QuestionSettled(id, sid)
            }

            // Verified: properties = { sessionID, info } (info.id kept as fallback).
            "session.deleted" -> {
                val sid = props.stringOrNull("sessionID")
                    ?: (props["info"] as? JsonObject)?.stringOrNull("id")
                    ?: return OcEvent.Unknown(type, json)
                OcEvent.SessionDeleted(sid)
            }

            "session.updated", "session.created" -> {
                val info = props["info"]?.jsonObject ?: return OcEvent.Unknown(type, json)
                val sid = info.stringOrNull("id") ?: return OcEvent.Unknown(type, json)
                OcEvent.SessionUpdated(sid, info.stringOrNull("title"))
            }

            else -> OcEvent.Unknown(type, json)
        }
    }

    /**
     * Parses one OpenCode **2.x** SSE `data:` payload from `GET /api/event`.
     * Confirmed against upstream v2.2.5 (packages/schema/src + session-event.ts):
     * each event is a flat object
     *   { "id", "created", "type", "metadata"?, "durable"?,
     *     "location": { "directory" }?, "data" }
     * i.e. `type` is top-level (NOT nested in metadata as older API docs
     * suggested) and the payload lives in `data`. Unknown types degrade to
     * [OcEvent.Unknown] exactly like the v1 path.
     */
    fun parseV2(json: JsonObject): OcEvent {
        val type = (json["type"] as? JsonPrimitive)?.contentOrNullSafe() ?: return OcEvent.Unknown("unknown", json)
        val props = (json["data"] as? JsonObject) ?: JsonObject(emptyMap())

        return when (type) {
            "server.connected" -> OcEvent.Connected(json)

            // v2 has no explicit session.idle/busy events: the execution
            // lifecycle carries that signal (data = { sessionID }).
            "session.execution.started" -> {
                val sid = props.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                OcEvent.SessionBusy(sid)
            }
            "session.execution.succeeded", "session.execution.interrupted" -> {
                val sid = props.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                OcEvent.SessionIdle(sid)
            }
            "session.execution.failed", "session.step.failed" -> {
                val sid = props.stringOrNull("sessionID")
                val message = (props["error"] as? JsonObject)?.stringOrNull("message")
                OcEvent.SessionError(sid, message)
            }

            // v2 schema (schema/src/permission.ts): data = Permission.Request
            // = { id, sessionID, action, resources[], save?[], message?, … }.
            // Human-readable label: action plus the resources it applies to
            // (the v1 "patterns" list became "resources").
            "permission.asked" -> {
                val id = props.stringOrNull("id") ?: return OcEvent.Unknown(type, json)
                val sid = props.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                val label = props.stringOrNull("message")
                    ?: props.stringOrNull("action")?.let { action ->
                        val resources = (props["resources"] as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
                            .orEmpty()
                        if (resources.isEmpty()) action else "$action: " + resources.joinToString(", ")
                    }
                OcEvent.PermissionAsked(id, sid, label)
            }

            // v2 schema: data = { sessionID, requestID, reply }.
            "permission.replied" -> {
                val id = props.stringOrNull("requestID") ?: props.stringOrNull("id")
                val sid = props.stringOrNull("sessionID")
                if (id == null || sid == null) return OcEvent.Unknown(type, json)
                OcEvent.PermissionReplied(id, sid)
            }

            // v2 generalizes v1 "questions" into "forms" (schema/src/form.ts):
            // data = { form: { id, sessionID, title, fields[] } }. The question
            // tool creates one string field per question; the field description
            // carries the question text, the form title is "Questions".
            "form.created" -> {
                val form = (props["form"] as? JsonObject) ?: return OcEvent.Unknown(type, json)
                val id = form.stringOrNull("id") ?: return OcEvent.Unknown(type, json)
                val sid = form.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                val prompt = form.stringOrNull("title")
                    ?: (form["fields"] as? kotlinx.serialization.json.JsonArray)
                        ?.firstOrNull()?.let { (it as? JsonObject)?.stringOrNull("description") }
                OcEvent.QuestionAsked(id, sid, prompt)
            }

            // v2 schema: data = { id, sessionID, answer? } - answered or
            // dismissed, either way the notification should come down.
            "form.replied", "form.cancelled" -> {
                val id = props.stringOrNull("id") ?: return OcEvent.Unknown(type, json)
                val sid = props.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                OcEvent.QuestionSettled(id, sid)
            }

            // v2 schema: data = { sessionID }.
            "session.deleted" -> {
                val sid = props.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                OcEvent.SessionDeleted(sid)
            }

            // v2 schemas: created = { sessionID, title?, … }, renamed =
            // { sessionID, title }.
            "session.created", "session.renamed" -> {
                val sid = props.stringOrNull("sessionID") ?: return OcEvent.Unknown(type, json)
                OcEvent.SessionUpdated(sid, props.stringOrNull("title"))
            }

            else -> OcEvent.Unknown(type, json)
        }
    }

    /**
     * Parses a raw v2 SSE `data:` line into an [OcEnvelope]: the event via
     * [parseV2], the project directory from `location.directory` (falling
     * back to [fallbackDirectory] when the event carries no location). Null
     * when the payload isn't valid JSON.
     */
    fun parseV2Event(data: String, fallbackDirectory: String?): OcEnvelope? = runCatching {
        val obj = json.parseToJsonElement(data).jsonObject
        val directory = ((obj["location"] as? JsonObject)
            ?.get("directory") as? JsonPrimitive)?.contentOrNullSafe()
            ?: fallbackDirectory
        OcEnvelope(directory, parseV2(obj))
    }.getOrNull()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNullSafe()

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { runCatching { it.content.toInt() }.getOrNull() }
