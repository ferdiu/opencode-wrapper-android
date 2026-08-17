package it.ferdiu.opencodewrapper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Everything the app needs to talk to a single OpenCode server: the base URL,
 * an optional auth header value, and the id of the session the user last had
 * open (so a notification tap or a cold start can jump back to it).
 *
 * This is intentionally the *only* persisted state of substance in the app -
 * per the design brief we do not keep a local copy of conversation content.
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
 * Wraps [EncryptedSharedPreferences] so the auth header/token never touches
 * disk in plaintext. The WebView gets cookies/session state from its own
 * cookie jar (see MainActivity); this store is what the background service
 * uses to authenticate independently of the WebView.
 *
 * Suppressing deprecation warnings because androidx.security:security-crypto is
 * deprecated but still the most straightforward option for a small app that
 * only needs to protect a single token; replacing it would pull in a larger
 * dependency or require platform-level Keystore code that would meaningfully
 * expand the project scope.
 */
@Suppress("DEPRECATION")
class ServerConfigStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            "opencode_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(): ServerConfig? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        return ServerConfig(
            baseUrl = url,
            authHeaderValue = prefs.getString(KEY_AUTH, null),
            lastSessionId = prefs.getString(KEY_LAST_SESSION, null),
        )
    }

    fun save(baseUrl: String, authHeaderValue: String?) {
        prefs.edit()
            .putString(KEY_URL, baseUrl.trimEnd('/'))
            .putString(KEY_AUTH, authHeaderValue?.takeIf { it.isNotBlank() })
            .apply()
    }

    fun setLastSessionId(sessionId: String?) {
        prefs.edit().putString(KEY_LAST_SESSION, sessionId).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_AUTH = "auth_header"
        private const val KEY_LAST_SESSION = "last_session_id"
    }
}
