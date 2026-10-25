package it.ferdiu.opencodewrapper.data

import android.content.Context

/**
 * Everything the app needs to talk to a single OpenCode server: the base URL,
 * an optional auth header value, and the id of the session the user last had
 * open (so a notification tap or a cold start can jump back to it).
 */
data class ServerConfig(
    val baseUrl: String,
    val authHeaderValue: String?,
    val lastSessionId: String? = null,
) {
    /** Normalized base URL with no trailing slash, e.g. "https://host:4096" */
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')
}

/**
 * Backwards-compatible facade over [ServerProfileStore] so existing consumers
 * (service, boot receiver, notification actions, Android Auto) keep working
 * unchanged: they only ever care about the *active* server.
 */
class ServerConfigStore(context: Context) {

    private val profiles = ServerProfileStore(context)

    fun get(): ServerConfig? = profiles.activeConfig()

    /** Legacy single-server save: upserts the active profile's URL/header,
     *  or creates a profile if none exists. New UI code should use
     *  [ServerProfileStore] directly. */
    fun save(baseUrl: String, authHeaderValue: String?) {
        val active = profiles.getActive()
        if (active != null) {
            profiles.update(active.copy(baseUrl = baseUrl.trimEnd('/'), authHeaderValue = authHeaderValue))
        } else {
            profiles.add(ServerProfile(name = baseUrl.trimEnd('/'), baseUrl = baseUrl.trimEnd('/'), authHeaderValue = authHeaderValue))
        }
    }

    fun setLastSessionId(sessionId: String?) = profiles.setLastSessionId(sessionId)

    fun clear() {
        profiles.getActiveId()?.let { profiles.delete(it) }
    }
}
