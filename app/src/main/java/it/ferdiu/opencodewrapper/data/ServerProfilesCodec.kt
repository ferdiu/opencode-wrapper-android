package it.ferdiu.opencodewrapper.data

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pure (Android-free) logic for persisting the server profile list:
 * JSON encode/decode and the one-time migration from the legacy single-server
 * keys. Kept separate from ServerProfileStore so it is unit-testable on the JVM
 * (EncryptedSharedPreferences is not).
 */
object ServerProfilesCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeProfiles(profiles: List<ServerProfile>): String =
        json.encodeToString(profiles)

    /** Defensive: corrupt storage must never crash the service. */
    fun decodeProfiles(raw: String?): List<ServerProfile> =
        runCatching {
            raw?.let { json.decodeFromString<List<ServerProfile>>(it) }
        }.getOrNull() ?: emptyList()

    data class LegacyMigration(
        val profiles: List<ServerProfile>,
        val activeProfileId: String,
        val lastSessionId: String?,
    )

    /** Converts the pre-multi-server storage (single URL + auth header) into
     *  a one-entry profile list. Name defaults to the URL host (with port). */
    fun migrateLegacy(legacyUrl: String, legacyAuth: String?, legacyLastSession: String?): LegacyMigration {
        val name = legacyUrl.toHttpUrlOrNull()?.let { httpUrl ->
            if (httpUrl.port != 80 && httpUrl.port != 443) "${httpUrl.host}:${httpUrl.port}" else httpUrl.host
        } ?: "Server"
        val profile = ServerProfile(
            name = name,
            baseUrl = legacyUrl,
            authHeaderValue = legacyAuth?.takeIf { it.isNotBlank() },
        )
        return LegacyMigration(
            profiles = listOf(profile),
            activeProfileId = profile.id,
            lastSessionId = legacyLastSession,
        )
    }
}
