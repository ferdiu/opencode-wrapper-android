package it.ferdiu.opencodewrapper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android-facing persistence for the server profile list. All data lives in
 * the same EncryptedSharedPreferences file the app already used
 * ("opencode_secure_prefs"); logic is in [ServerProfilesCodec] so it stays
 * unit-testable. Performs the legacy single-server migration lazily on first
 * access.
 */
@Suppress("DEPRECATION") // security-crypto is deprecated but still the simplest
// option for this app's scale; see ServerConfigStore's original rationale.
class ServerProfileStore(context: Context) {

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

    fun list(): List<ServerProfile> {
        migrateLegacyIfNeeded()
        return ServerProfilesCodec.decodeProfiles(prefs.getString(KEY_PROFILES, null))
    }

    fun get(id: String): ServerProfile? = list().firstOrNull { it.id == id }

    fun add(profile: ServerProfile) {
        val profiles = list() + profile
        val wasEmpty = profiles.size == 1
        prefs.edit().putString(KEY_PROFILES, ServerProfilesCodec.encodeProfiles(profiles)).apply()
        if (wasEmpty) setActive(profile.id) // first server becomes active automatically
    }

    fun update(profile: ServerProfile) {
        val profiles = list().map { if (it.id == profile.id) profile else it }
        prefs.edit().putString(KEY_PROFILES, ServerProfilesCodec.encodeProfiles(profiles)).apply()
    }

    fun delete(id: String) {
        val profiles = list().filterNot { it.id == id }
        prefs.edit().putString(KEY_PROFILES, ServerProfilesCodec.encodeProfiles(profiles)).apply()
        if (getActiveId() == id) setActive(null)
    }

    fun getActive(): ServerProfile? = getActiveId()?.let { get(it) }

    fun setActive(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun getActiveId(): String? {
        // Read paths that bypass list() (service, BootReceiver via
        // ServerConfigStore) must also trigger the lazy legacy migration,
        // otherwise upgraded single-server users see a null config until a
        // UI path calls list(). Idempotent and cheap.
        migrateLegacyIfNeeded()
        return prefs.getString(KEY_ACTIVE, null)
    }

    /** What all existing consumers need: the active server as a plain config. */
    fun activeConfig(): ServerConfig? =
        getActive()?.toServerConfig(lastSessionId = getLastSessionId())

    fun getLastSessionId(): String? {
        migrateLegacyIfNeeded()
        return prefs.getString(KEY_LAST_SESSION, null)
    }

    fun setLastSessionId(sessionId: String?) {
        prefs.edit().putString(KEY_LAST_SESSION, sessionId).apply()
    }

    /** One-time conversion of the legacy single-server keys. */
    private fun migrateLegacyIfNeeded() {
        if (prefs.contains(KEY_PROFILES)) return
        val legacyUrl = prefs.getString(LEGACY_URL, null) ?: return
        val legacyLastSession = prefs.getString(LEGACY_LAST_SESSION, null)
        val migration = ServerProfilesCodec.migrateLegacy(
            legacyUrl = legacyUrl,
            legacyAuth = prefs.getString(LEGACY_AUTH, null),
            legacyLastSession = legacyLastSession,
        )
        prefs.edit()
            .putString(KEY_PROFILES, ServerProfilesCodec.encodeProfiles(migration.profiles))
            .putString(KEY_ACTIVE, migration.activeProfileId)
            .putString(KEY_LAST_SESSION, migration.lastSessionId)
            .remove(LEGACY_URL)
            .remove(LEGACY_AUTH)
            .apply()
    }

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE = "active_profile_id"
        private const val KEY_LAST_SESSION = "last_session_id"
        private const val LEGACY_URL = "server_url"
        private const val LEGACY_AUTH = "auth_header"
        // LEGACY_LAST_SESSION intentionally equals KEY_LAST_SESSION ("last_session_id"):
        // the legacy value is read before the edit writes the same key, so the
        // session id is preserved. Do NOT remove that key in the migration edit.
        private const val LEGACY_LAST_SESSION = "last_session_id"
    }
}
