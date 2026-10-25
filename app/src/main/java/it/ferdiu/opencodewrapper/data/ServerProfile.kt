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
) {
    fun toServerConfig(lastSessionId: String? = null): ServerConfig {
        val header = if (!username.isNullOrBlank()) {
            Credentials.basic(username, password.orEmpty())
        } else {
            authHeaderValue?.takeIf { it.isNotBlank() }
        }
        return ServerConfig(
            baseUrl = baseUrl,
            authHeaderValue = header,
            lastSessionId = lastSessionId,
        )
    }
}
