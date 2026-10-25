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
