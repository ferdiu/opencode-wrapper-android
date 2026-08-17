package com.opencode.wrapper.api

import android.util.Log
import com.opencode.wrapper.data.ServerConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real HTTP/SSE implementation, targeting the endpoints verified against the
 * anomalyco/opencode (formerly sst/opencode) source at tag v1.18.18:
 *
 *  - `GET  /global/health`            confirmed  - liveness/version check
 *  - `GET  /event`                    confirmed  - instance-scoped SSE stream
 *      (this is the *non*-legacy path: legacy is `GET /global/event`, which
 *      this app deliberately avoids per the design brief). Adding
 *      `?session={id}` filters the stream to that session's events plus
 *      connection-level `server.*` events - this filtering was added
 *      specifically for the V2 SDK (upstream PR #6729,
 *      "@opencode-ai/sdk/v2 - Fully supported") and is the "V2 event API"
 *      referenced in the brief; there is no separate `/v2/event` URL.
 *  - `GET  /session/status`           confirmed  - all session statuses
 *  - `GET  /session/{id}`             confirmed  - single session detail
 *  - `GET  /session`                  confirmed  - list sessions
 *
 * ASSUMPTION CALLED OUT EXPLICITLY: I could not find a documented, stable
 * "durable event log with a replay cursor" endpoint distinct from the
 * session/status/detail endpoints above. What v1.18.18 actually exposes for
 * "catch up after a reconnect" is: (a) session status/detail, which is
 * itself durable server state, and (b) the session-scoped `/event` filter.
 * So the recovery strategy implemented here is: reconnect the SSE stream,
 * and separately re-fetch session status/detail for whatever sessions we
 * care about to detect any idle/error/permission transition that happened
 * while disconnected. If a future OpenCode release adds an explicit
 * cursor/replay endpoint, only [getSessionSnapshot]/[listActiveSessions]
 * and the reconnect flow in OpenCodeEventService should need to change.
 *
 * Auth: OpenCode's own HTTP server does not document a built-in bearer-auth
 * scheme for v1.18.18 (the `/auth/{providerID}` endpoints configure *LLM
 * provider* credentials, not access to the OpenCode server itself). Server
 * access control is typically handled by whatever reverse proxy sits in
 * front of `opencode serve`. So this client just attaches the raw header
 * the user configured, if any, on every request - it works with any
 * bearer/API-key style proxy without hard-coding a specific header name.
 */
class OpenCodeClientV2(
    private val config: ServerConfig,
    private val http: OkHttpClient = defaultHttpClient(),
) : OpenCodeClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun events(sessionId: String?): Flow<OcEvent> = callbackFlow {
        val url = buildString {
            append(config.normalizedBaseUrl)
            append("/event")
            if (sessionId != null) append("?session=").append(sessionId)
        }

        val request = authedRequest(url).build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "SSE connected: $url")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.isBlank()) return
                val parsed = runCatching {
                    val element = json.parseToJsonElement(data)
                    OcEventParser.parse(element.jsonObject)
                }.onFailure { e ->
                    Log.w(TAG, "Skipping malformed SSE payload: ${e.message}")
                }.getOrNull() ?: return
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
        val request = authedRequest("${config.normalizedBaseUrl}/global/health").build()
        executeAsync(request).use { it.isSuccessful }
    }.getOrDefault(false)

    override suspend fun getSessionSnapshot(sessionId: String): SessionSnapshot? = runCatching {
        val request = authedRequest("${config.normalizedBaseUrl}/session/$sessionId").build()
        val body = executeAsync(request).use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null
        val obj = json.parseToJsonElement(body).jsonObject
        sessionSnapshotFromInfo(obj)
    }.getOrNull()

    override suspend fun listActiveSessions(): List<SessionSnapshot> = runCatching {
        val statusRequest = authedRequest("${config.normalizedBaseUrl}/session/status").build()
        val body = executeAsync(statusRequest).use { resp ->
            if (!resp.isSuccessful) return@runCatching emptyList()
            resp.body?.string()
        } ?: return@runCatching emptyList()

        val root = json.parseToJsonElement(body)
        val entries = when {
            root is kotlinx.serialization.json.JsonArray -> root
            else -> root.jsonObject["sessions"]?.jsonArray ?: return@runCatching emptyList()
        }
        entries.mapNotNull { runCatching { sessionSnapshotFromInfo(it.jsonObject) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun sessionSnapshotFromInfo(obj: JsonObject): SessionSnapshot {
        val id = obj["id"]?.jsonPrimitive?.content
            ?: obj["sessionID"]?.jsonPrimitive?.content
            ?: error("missing session id")
        val title = runCatching { obj["title"]?.jsonPrimitive?.content }.getOrNull()
        val statusType = runCatching {
            (obj["status"]?.jsonObject?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.getOrNull() ?: "unknown"
        return SessionSnapshot(id, title, statusType)
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
        val call = http.newCall(request)
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
