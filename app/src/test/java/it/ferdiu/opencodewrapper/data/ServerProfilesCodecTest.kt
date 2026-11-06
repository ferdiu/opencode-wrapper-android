package it.ferdiu.opencodewrapper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerProfilesCodecTest {

    private fun profile(id: String, name: String = id) = ServerProfile(
        id = id, name = name, baseUrl = "http://$id:4096",
        username = "u", password = "p",
    )

    @Test
    fun `encode then decode round-trips the profile list`() {
        val profiles = listOf(profile("a", "Home"), profile("b", "Work"))
        val decoded = ServerProfilesCodec.decodeProfiles(ServerProfilesCodec.encodeProfiles(profiles))
        assertEquals(profiles, decoded)
    }

    @Test
    fun `decode of null or malformed json yields empty list`() {
        assertEquals(emptyList<ServerProfile>(), ServerProfilesCodec.decodeProfiles(null))
        assertEquals(emptyList<ServerProfile>(), ServerProfilesCodec.decodeProfiles("not json {{{"))
        assertEquals(emptyList<ServerProfile>(), ServerProfilesCodec.decodeProfiles("{}"))
    }

    @Test
    fun `profile serialized without v1CompatMode deserializes with default true`() {
        // Profiles saved before the field existed must stay v1-compatible.
        val legacyJson = """[{"id":"a","name":"Home","baseUrl":"http://a:4096"}]"""
        val decoded = ServerProfilesCodec.decodeProfiles(legacyJson)
        assertEquals(1, decoded.size)
        assertEquals(true, decoded.single().v1CompatMode)
        assertEquals(true, decoded.single().toServerConfig().v1CompatMode)
    }

    @Test
    fun `explicit v1CompatMode false round-trips`() {
        val profiles = listOf(profile("a").copy(v1CompatMode = false))
        val decoded = ServerProfilesCodec.decodeProfiles(ServerProfilesCodec.encodeProfiles(profiles))
        assertEquals(false, decoded.single().v1CompatMode)
        assertEquals(false, decoded.single().toServerConfig().v1CompatMode)
    }

    @Test
    fun `migrateLegacy builds one profile from legacy keys`() {
        val m = ServerProfilesCodec.migrateLegacy(
            legacyUrl = "http://192.168.1.10:4096/",
            legacyAuth = "Bearer xyz",
            legacyLastSession = "ses_1",
        )
        assertEquals("192.168.1.10:4096", m.profiles.single().name)
        assertEquals("http://192.168.1.10:4096/", m.profiles.single().baseUrl)
        assertEquals("Bearer xyz", m.profiles.single().authHeaderValue)
        assertNull(m.profiles.single().username)
        assertEquals(m.profiles.single().id, m.activeProfileId)
        assertEquals("ses_1", m.lastSessionId)
    }

    @Test
    fun `migrateLegacy names profile host with port when present`() {
        val m = ServerProfilesCodec.migrateLegacy("https://box.tailnet.ts.net:8443", null, null)
        assertEquals("box.tailnet.ts.net:8443", m.profiles.single().name)
        assertNull(m.profiles.single().authHeaderValue)
    }
}
