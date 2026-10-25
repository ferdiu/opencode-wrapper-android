package it.ferdiu.opencodewrapper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerProfileTest {

    @Test
    fun `username and password derive Basic auth header`() {
        val p = ServerProfile(
            id = "1", name = "Home", baseUrl = "http://h:4096/",
            username = "alice", password = "s3cret",
        )
        val config = p.toServerConfig()
        // base64("alice:s3cret") == "YWxpY2U6czNjcmV0"
        assertEquals("Basic YWxpY2U6czNjcmV0", config.authHeaderValue)
        // ServerConfig stores baseUrl as given; normalizedBaseUrl trims the slash.
        assertEquals("http://h:4096", config.normalizedBaseUrl)
    }

    @Test
    fun `blank username falls back to raw auth header`() {
        val p = ServerProfile(
            id = "1", name = "Home", baseUrl = "http://h:4096",
            username = "  ", password = "ignored",
            authHeaderValue = "X-Token: abc",
        )
        assertEquals("X-Token: abc", p.toServerConfig().authHeaderValue)
    }

    @Test
    fun `no credentials yields null header`() {
        val p = ServerProfile(id = "1", name = "Home", baseUrl = "http://h:4096")
        assertNull(p.toServerConfig().authHeaderValue)
    }

    @Test
    fun `lastSessionId passes through`() {
        val p = ServerProfile(id = "1", name = "Home", baseUrl = "http://h:4096")
        assertEquals("ses_9", p.toServerConfig(lastSessionId = "ses_9").lastSessionId)
    }
}
