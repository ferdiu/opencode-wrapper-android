package it.ferdiu.opencodewrapper.data

import kotlinx.serialization.Serializable
import okhttp3.Credentials
import java.util.UUID

/**
 * A saved OpenCode server: display name, base URL, and either username/password
 * (OpenCode's --username/--password basic auth) or a raw auth header for
 * advanced setups. Username/password take precedence when username is non-blank.
 *
 * Persisted as JSON inside ServerProfileStore; consumers never see this type
 * directly - they get a plain [ServerConfig] via [toServerConfig].
 */
@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null,
    val authHeaderValue: String? = null,
    /** Whether the API detector may fall back to the OpenCode 1.x protocol
     *  when the server doesn't answer the 2.x probe. Default true so profiles
     *  saved before this field existed (absent key on deserialization) keep
     *  working against 1.x servers. */
    val v1CompatMode: Boolean = true,
) {
    fun toServerConfig(lastSessionId: String? = null): ServerConfig {
        val header = if (!username.isNullOrBlank()) {
            // Explicit UTF-8: the 2-arg overload encodes as ISO-8859-1 and
            // would mangle non-ASCII credentials.
            Credentials.basic(username, password.orEmpty(), Charsets.UTF_8)
        } else {
            authHeaderValue?.takeIf { it.isNotBlank() }
        }
        return ServerConfig(
            baseUrl = baseUrl,
            authHeaderValue = header,
            lastSessionId = lastSessionId,
            v1CompatMode = v1CompatMode,
        )
    }
}
