package it.ferdiu.opencodewrapper.api

import android.util.Log
import it.ferdiu.opencodewrapper.data.ServerConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Implements the OpenCode **2.x** server API (`/api/…` endpoints), verified
 * against upstream anomalyco/opencode tag v2.2.5
 * (packages/protocol/src/groups and packages/schema):
 *
 *  - `GET  /api/info`                              - server identity (also
 *     the API-generation probe used by [ApiVersionDetector]); v1 servers 404
 *  - `GET  /api/event`                             - SSE stream of flat
 *     events { id, created, type, location?, data } across all locations
 *     (replaces v1 /global/event)
 *  - `GET  /api/session`                           - { data: SessionInfo[],
 *     cursor }; query: limit, directory, archived, parentID, order, …
 *  - `GET  /api/session/{id}`                      - { data: SessionInfo }
 *  - `GET  /api/session/active`                    - { data: { sessionID:
 *     { type: "running" } } } - the v2 busy picture (no /session/status)
 *  - `GET  /api/session/{id}/message`              - { data: Message[],
 *     cursor }; order defaults to desc upstream, so we request asc to keep
 *     the oldest-first assumption of [MessageTextExtractor]
 *  - `POST /api/session/{id}/prompt`               - body PromptInput.Prompt
 *     = { text, … } (replaces v1 /session/{id}/message {parts})
 *  - `GET  /api/session/{id}/permission`           - { data: Request[] };
 *  - `POST /api/session/{id}/permission/{rid}/reply` - body { decision:
 *     "once"|"always"|"reject", message? } -> 204 (Permission.Reply literals
 *     confirmed in schema/src/permission.ts and identical to
 *     [PermissionDecision.apiValue])
 *  - `GET  /api/session/{id}/form` / `…/form/{fid}` - pending forms (v2
 *     generalizes v1 "questions" into "forms")
 *  - `POST /api/session/{id}/form/{fid}/reply`     - body Form.Reply =
 *     { answer: { fieldKey: value } } -> 204
 *  - `GET  /api/project`                           - bare array of
 *     ProjectInfo { id, canonical, name?, icon?, … } (note: "worktree"
 *     became "canonical")
 *
 * Session-scoped routes resolve the session's project location
 * server-side (session-location middleware), so unlike [OpenCodeClientV1]
 * no `?directory=` scoping parameter is needed on replies.
 *
 * Status model: v2 has no session.status endpoint. Busy/idle is derived
 * from SessionInfo.time - time.busy is set while an execution runs and
 * time.idle holds the last completed execution's end.
 *
 * Auth: OpenCode 2.x serves HTTP Basic auth with username `opencode` and
 * password from OPENCODE_PASSWORD. The app's raw auth-header setting
 * already produces "Authorization: Basic base64(opencode:password)", so
 * no auth handling changes were needed.
 */
class OpenCodeClientV2(
    private val config: ServerConfig,
    private val http: OkHttpClient = defaultHttpClient(),
) : OpenCodeClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Bounded client for one-shot REST calls (replies, snapshots, health).
     *  The infinite-timeout [http] stays dedicated to the SSE stream only. */
    private val restHttp = http.newBuilder()
        .callTimeout(REST_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun events(): Flow<OcEnvelope> = callbackFlow {
        // GET /api/event streams every location's events; each SSE data
        // line is one flat event object whose `location.directory` (when
        // present) plays the role of the v1 /global/event envelope's
        // "directory" field. Volatile by contract (slow consumers overflow,
        // disconnects miss events) - same operational semantics as v1, so
        // the service-level reconnect + recovery-pass strategy stays valid.
        val url = buildString {
            append(config.normalizedBaseUrl)
            append("/api/event")
        }

        val request = authedRequest(url).build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "SSE connected: $url (HTTP ${response.code})")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.isBlank()) return
                val parsed = OcEventParser.parseV2Event(data, fallbackDirectory = null)
                if (parsed == null) {
                    Log.w(TAG, "Skipping malformed SSE payload")
                    return
                }
                trySend(parsed)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IOException("SSE stream failed with response=$response"))
            }
        }

        val source = EventSources.createFactory(http).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    override suspend fun healthCheck(): Boolean = runCatching {
        val request = authedRequest("${config.normalizedBaseUrl}/api/info").build()
        val body = executeAsync(request).use { resp ->
            if (!resp.isSuccessful) return@runCatching false
            resp.body.string()
        }
        // A v1 server (or an SPA catch-all) can answer 200 without being v2;
        // only a JSON object with a string version field counts.
        val version = runCatching {
            json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.content
        }.getOrNull()
        version != null
    }.getOrDefault(false)

    override suspend fun getSessionSnapshot(sessionId: String): SessionSnapshot? = runCatching {
        val request = authedRequest("${config.normalizedBaseUrl}/api/session/$sessionId").build()
        val body = executeAsync(request).use { resp ->
            if (!resp.isSuccessful) return null
            resp.body.string()
        }
        if (body.isEmpty()) return null
        val obj = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return null
        sessionSnapshotFromInfo(obj)
    }.getOrNull()

    override suspend fun listActiveSessions(): List<SessionSnapshot> = runCatching {
        // GET /api/session/active = foreground drains owned by this process;
        // absence from the map means inactive, so every entry is "busy".
        val request = authedRequest("${config.normalizedBaseUrl}/api/session/active").build()
        val body = executeAsync(request).use { resp ->
            if (!resp.isSuccessful) return@runCatching emptyList()
            resp.body.string()
        }
        if (body.isEmpty()) return@runCatching emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val entries = root["data"]?.jsonObject ?: return@runCatching emptyList()
        entries.keys.map { SessionSnapshot(it, title = null, statusType = "busy") }
    }.getOrDefault(emptyList())

    override suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: PermissionDecision,
        directory: String?,
    ): Boolean = runCatching {
        // Confirmed against upstream v2.2.5: session-scoped route (the
        // session's location is resolved server-side), body { decision,
        // message? } -> 204. Permission.Reply literals "once"/"always"/
        // "reject" match PermissionDecision.apiValue exactly.
        val url = "${config.normalizedBaseUrl}/api/session/$sessionId/permission/$requestId/reply"
        val payload = buildJsonObject {
            put("decision", decision.apiValue)
            put("message", JsonNull)
        }.toString()
        val request = authedRequest(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        executeAsync(request).use { it.isSuccessful }
    }.getOrDefault(false)

    override suspend fun replyQuestion(
        sessionId: String,
        requestId: String,
        answer: String,
        directory: String?,
    ): Boolean = runCatching {
        // v2 forms are keyed maps: { answer: { fieldKey: value } }. The
        // question tool names its fields q0, q1, … (packages/core/src/tool/
        // plugin/question.ts); resolve the actual first field key from the
        // form detail when available, falling back to "q0".
        val key = formFirstFieldKey(sessionId, requestId) ?: "q0"
        val url = "${config.normalizedBaseUrl}/api/session/$sessionId/form/$requestId/reply"
        val payload = buildJsonObject {
            putJsonObject("answer") {
                put(key, answer)
            }
        }.toString()
        val request = authedRequest(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        executeAsync(request).use { it.isSuccessful }
    }.getOrDefault(false)

    override suspend fun listProjects(): List<OcProject> {
        val request = authedRequest("${config.normalizedBaseUrl}/api/project").build()
        val body = executeAsync(request).use { resp ->
            if (!resp.isSuccessful) throw IOException("listProjects: HTTP ${resp.code}")
            resp.body.string()
        }
        if (body.isEmpty()) return emptyList()
        val root = json.parseToJsonElement(body)
        if (root !is kotlinx.serialization.json.JsonArray) return emptyList()
        return root.mapNotNull { runCatching {
            val obj = it.jsonObject
            // v2 renamed "worktree" to "canonical"; OcProject.worktree keeps
            // the v1 name because the rest of the app (car grouping, session
            // scoping) is built around it.
            val worktree = obj["canonical"]?.jsonPrimitive?.content ?: error("missing canonical")
            val iconColor = runCatching {
                (obj["icon"] as? JsonObject)?.get("color")
                    ?.takeIf { color -> color !is JsonNull }?.jsonPrimitive?.content
            }.getOrNull()
            OcProject(
                id = obj["id"]?.jsonPrimitive?.content ?: worktree,
                worktree = worktree,
                label = obj["name"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                    ?: worktree.substringAfterLast('/').ifEmpty { "Global" },
                iconColor = iconColor,
            )
        }.getOrNull() }
    }

    override suspend fun listSessions(limit: Int, directory: String?): List<OcSession> {
        val entries = fetchSessionInfos(limit, directory)
        return entries.mapNotNull { entry ->
            runCatching {
                val obj = entry.jsonObject
                // Car lists must not show sub-agent sessions (parentID) or
                // archived ones (time.archived); both are pure noise there.
                if (obj["parentID"]?.takeIf { it !is JsonNull } != null) return@runCatching null
                val time = obj["time"] as? JsonObject
                if (time?.get("archived")?.takeIf { it !is JsonNull } != null) return@runCatching null
                ocSessionFromInfo(obj)
            }.getOrNull()
        }
    }

    override suspend fun listSessionStatuses(directory: String?): Map<String, String> = runCatching {
        // No v2 equivalent of v1 GET /session/status: derive busy/idle from
        // SessionInfo.time (time.busy set while an execution runs; time.idle
        // = last completed execution's end).
        fetchSessionInfos(limit = 50, directory = directory).mapNotNull { entry ->
            val obj = entry.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            id to statusTypeFromTime(obj["time"] as? JsonObject)
        }.toMap()
    }.getOrDefault(emptyMap())

    /** Raw `data` array of GET /api/session for one project instance. */
    private suspend fun fetchSessionInfos(limit: Int, directory: String?): kotlinx.serialization.json.JsonArray {
        val httpUrl = "${config.normalizedBaseUrl}/api/session".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .apply { if (directory != null) addQueryParameter("directory", directory) }
            .build()
        val request = authedRequest(httpUrl.toString()).build()
        val body = executeAsync(request).use { resp ->
            if (!resp.isSuccessful) throw IOException("listSessions: HTTP ${resp.code}")
            resp.body.string()
        }
        if (body.isEmpty()) return kotlinx.serialization.json.JsonArray(emptyList())
        return json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?: kotlinx.serialization.json.JsonArray(emptyList())
    }

    override suspend fun getLastMessage(sessionId: String, directory: String?): SessionMessage? {
        // order defaults to desc upstream; request asc to keep the
        // oldest-first assumption MessageTextExtractor was written against.
        val httpUrl = "${config.normalizedBaseUrl}/api/session/$sessionId/message".toHttpUrl().newBuilder()
            .addQueryParameter("order", "asc")
            .addQueryParameter("limit", "200")
            .build()
        val request = authedRequest(httpUrl.toString()).build()
        val body = executeAsync(request).use { resp ->
            if (!resp.isSuccessful) throw IOException("getLastMessage: HTTP ${resp.code}")
            resp.body.string()
        }
        if (body.isEmpty()) return null
        val messages = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return null
        return MessageTextExtractor.lastReadableMessage(messages)
    }

    override suspend fun sendPrompt(sessionId: String, text: String, directory: String?): Boolean = runCatching {
        // Confirmed against upstream v2.2.5: POST /api/session/{id}/prompt
        // with PromptInput.Prompt = { text, files?, agents?, skills? }.
        val url = "${config.normalizedBaseUrl}/api/session/$sessionId/prompt"
        val payload = buildJsonObject {
            put("text", text)
        }.toString()
        val request = authedRequest(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        executeAsync(request).use { it.isSuccessful }
    }.getOrDefault(false)

    override suspend fun listPendingPermissions(sessionId: String, directory: String?): List<PendingPermission> = runCatching {
        // Session-scoped: the endpoint only returns this session's pending
        // requests, so no directory scoping is required (unlike v1 GET
        // /permission?directory= which needed the instance query param).
        val request = authedRequest("${config.normalizedBaseUrl}/api/session/$sessionId/permission").build()
        val body = executeAsync(request).use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body.string()
        }
        if (body.isEmpty()) return emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val entries = root["data"]?.jsonArray ?: return emptyList()
        entries.filter { entry ->
            (entry as? JsonObject)?.get("sessionID")
                ?.takeIf { it !is JsonNull }
                ?.jsonPrimitive?.contentOrNull() == sessionId
        }
            .mapNotNull { runCatching { pendingPermissionFromInfo(it.jsonObject) }.getOrNull() }
    }.getOrDefault(emptyList())

    /** Fetches a form's detail and returns its first field key, or null when
     *  the form is gone/unreachable (the "q0" fallback then applies). */
    private suspend fun formFirstFieldKey(sessionId: String, formId: String): String? = runCatching {
        val request = authedRequest("${config.normalizedBaseUrl}/api/session/$sessionId/form/$formId").build()
        val body = executeAsync(request).use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body.string()
        }
        if (body.isEmpty()) return@runCatching null
        val form = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return@runCatching null
        (form["fields"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()?.let { (it as? JsonObject)?.get("key") }
            ?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull() }
    }.getOrNull()

    private fun pendingPermissionFromInfo(obj: JsonObject): PendingPermission {
        val id = obj["id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?: error("missing permission id")
        // v2: { action, resources[] } replaces v1's { permission, patterns }.
        val action = runCatching {
            obj["action"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        }.getOrNull() ?: "permission"
        val resources = (obj["resources"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { resource ->
                resource.takeIf { it !is JsonNull }?.let { (it as? JsonPrimitive)?.contentOrNull() }
            }
            .orEmpty()
        val label = if (resources.isEmpty()) action else "$action: " + resources.joinToString(", ")
        return PendingPermission(id, label)
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

    private fun ocSessionFromInfo(obj: JsonObject): OcSession {
        val id = obj["id"]?.jsonPrimitive?.content ?: error("missing session id")
        // JsonNull.content returns the literal "null" (it does not throw), so
        // JSON nulls must be filtered out before reading the primitive.
        val title = runCatching {
            obj["title"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        }.getOrNull()
        val directory = runCatching {
            (obj["location"] as? JsonObject)?.get("directory")
                ?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        }.getOrNull()
        val updatedAt = runCatching {
            (obj["time"] as? JsonObject)?.get("updated")?.jsonPrimitive?.long ?: 0L
        }.getOrDefault(0L)
        return OcSession(id, title, directory?.substringAfterLast('/'), directory, updatedAt)
    }

    private fun sessionSnapshotFromInfo(obj: JsonObject): SessionSnapshot {
        val id = obj["id"]?.jsonPrimitive?.content ?: error("missing session id")
        val title = runCatching { obj["title"]?.jsonPrimitive?.content }.getOrNull()
        return SessionSnapshot(id, title, statusTypeFromTime(obj["time"] as? JsonObject))
    }

    /** busy when an execution is running (time.busy newer than time.idle). */
    private fun statusTypeFromTime(time: JsonObject?): String {
        val busy = time?.get("busy")?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
        val idle = time?.get("idle")?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
        return if (busy != null && (idle == null || busy > idle)) "busy" else "idle"
    }

    private fun authedRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        config.authHeaderValue?.takeIf { it.isNotBlank() }?.let { raw ->
            // Accept either "HeaderName: value" or a bare value, in which
            // case we default to a standard Authorization bearer header.
            val colonIndex = raw.indexOf(':')
            if (colonIndex > 0) {
                builder.header(raw.substring(0, colonIndex).trim(), raw.substring(colonIndex + 1).trim())
            } else {
                builder.header("Authorization", raw.trim())
            }
        }
        return builder
    }

    private suspend fun executeAsync(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = restHttp.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response)
            }
        })
    }

    companion object {
        private const val TAG = "OpenCodeClientV2"
        private const val REST_CALL_TIMEOUT_SECONDS = 30L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            // SSE connections are long-lived by design; disable the read
            // timeout for the stream itself while keeping connect/write sane.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
