package it.ferdiu.opencodewrapper.api

import it.ferdiu.opencodewrapper.data.ServerConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The server rejected the 2.x probe while the profile forbids v1 fallback. */
class ApiVersionException(message: String) : IOException(message)

/**
 * Picks the right [OpenCodeClient] implementation per server profile.
 *
 * Detection probes `GET {baseUrl}/api/info`: OpenCode 2.x answers 200 with a
 * JSON body containing a `version` field; OpenCode 1.x has no `/api/…` routes
 * at all (verified: no such routes in the v1.19.5 source), so anything else
 * - 404, SPA catch-all HTML, malformed body, network error - means "not v2".
 *
 * Honours ServerProfile.v1CompatMode ([ServerConfig.v1CompatMode]):
 *  - true  → probe; fall back to [OpenCodeClientV1] on any probe failure.
 *  - false → probe; if the server doesn't answer as v2, throw
 *    [ApiVersionException] so the caller can surface "not an OpenCode 2.x
 *    server" instead of silently downgrading.
 *
 * Results are cached per normalized base URL so the foreground service
 * doesn't re-probe on every reconnect. [invalidate] is called from the
 * profile store on any add/update/delete (editing a server clears the cache,
 * so a changed URL or toggle is picked up immediately).
 */
object ApiVersionDetector {

    enum class ServerApi { V1, V2 }

    private val cache = ConcurrentHashMap<String, ServerApi>()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val probeHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Last detection result for [baseUrl], or null when not probed yet. */
    fun cached(baseUrl: String): ServerApi? = cache[baseUrl.trimEnd('/')]

    /** Clears every cached detection result (profile add/update/delete). */
    fun invalidate() = cache.clear()

    /**
     * Probes the server (unless cached) and returns the detected generation.
     * Never throws for "server is not v2" - that outcome is [ServerApi.V1];
     * transport failures also degrade to V1 (the lenient default the service
     * relies on, matching the v1 client's runCatching philosophy).
     */
    suspend fun detect(config: ServerConfig): ServerApi {
        val key = config.normalizedBaseUrl
        cache[key]?.let { return it }
        val detected = probe(config)
        cache[key] = detected
        return detected
    }

    /**
     * Builds the client for [config] honouring v1CompatMode. Throws
     * [ApiVersionException] when the profile requires v2 and the server
     * doesn't speak it.
     */
    suspend fun clientFor(config: ServerConfig): OpenCodeClient =
        when (detect(config)) {
            ServerApi.V2 -> OpenCodeClientV2(config)
            ServerApi.V1 ->
                if (config.v1CompatMode) {
                    OpenCodeClientV1(config)
                } else {
                    throw ApiVersionException(
                        "${config.normalizedBaseUrl} does not answer the OpenCode 2.x API " +
                            "(GET /api/info) and this server is configured to require v2",
                    )
                }
        }

    /**
     * Builds the web-UI deep link for a session, generation-aware:
     *  - v1 web app: `/{base64url(directory)}/session/{id}` (verified against
     *    the v1.18.18–v1.19.5 web app router).
     *  - v2 web app: `/server/{base64url(baseUrl)}/session/{id}` (verified
     *    against the v2.2.5 web app: the serverKey segment is the URL-safe,
     *  unpadded base64 of the server's own base URL; the project directory
     *    is not part of the route).
     *
     * When no detection result is cached yet, defaults to the v1 format only
     * for v1-compat profiles; strict-v2 profiles get the v2 format.
     */
    fun webUiSessionUrl(config: ServerConfig, sessionId: String, directory: String?): String {
        val base = config.normalizedBaseUrl
        val api = cached(base) ?: if (config.v1CompatMode) ServerApi.V1 else ServerApi.V2
        return when (api) {
            ServerApi.V1 -> {
                val dir = directory ?: return "$base/session/$sessionId"
                "$base/${base64UrlEncode(dir)}/session/$sessionId"
            }
            ServerApi.V2 -> "$base/server/${base64UrlEncode(base)}/session/$sessionId"
        }
    }

    /** URL-safe base64 without padding, matching both web apps' encoding. */
    private fun base64UrlEncode(value: String): String =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    /** 200 + parseable JSON `version` field → V2; absolutely anything else → V1. */
    private suspend fun probe(config: ServerConfig): ServerApi {
        val builder = Request.Builder().url("${config.normalizedBaseUrl}/api/info")
        config.authHeaderValue?.takeIf { it.isNotBlank() }?.let { raw ->
            // Same raw-header logic as the clients: "HeaderName: value" or a
            // bare value sent as Authorization.
            val colonIndex = raw.indexOf(':')
            if (colonIndex > 0) {
                builder.header(raw.substring(0, colonIndex).trim(), raw.substring(colonIndex + 1).trim())
            } else {
                builder.header("Authorization", raw.trim())
            }
        }
        return runCatching {
            val body = executeAsync(probeHttp, builder.build()).use { resp ->
                if (!resp.isSuccessful) return@runCatching ServerApi.V1
                resp.body.string()
            }
            val version = runCatching {
                json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.content
            }.getOrNull()
            if (version != null) ServerApi.V2 else ServerApi.V1
        }.getOrDefault(ServerApi.V1)
    }

    private suspend fun executeAsync(http: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { cont ->
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

    private const val PROBE_TIMEOUT_SECONDS = 5L
}
