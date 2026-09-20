package it.ferdiu.opencodewrapper.api

import it.ferdiu.opencodewrapper.data.ServerConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenCodeClientV2SessionsTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun client(): OpenCodeClientV2 =
        OpenCodeClientV2(ServerConfig(baseUrl = server.url("/").toString().trimEnd('/'), authHeaderValue = null))

    @Test
    fun `list projects maps id worktree and basename label`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            [
              { "id": "abc", "worktree": "/home/dev/shop-api", "vcs": "git" },
              { "id": "global", "worktree": "/" }
            ]
            """.trimIndent()
        ))
        val projects = client().listProjects()
        assertEquals(
            listOf(OcProject("abc", "/home/dev/shop-api", "shop-api"), OcProject("global", "/", "Global")),
            projects
        )
    }

    @Test
    fun `list sessions maps id title directory and project basename`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            [
              { "id": "ses_1", "title": "Fix login bug", "directory": "/home/dev/shop-api", "time": { "updated": 200 } },
              { "id": "ses_2", "title": null, "directory": "/home/dev/blog" }
            ]
            """.trimIndent()
        ))
        val sessions = client().listSessions(directory = "/home/dev/shop-api")
        assertEquals(2, sessions.size)
        assertEquals(OcSession("ses_1", "Fix login bug", "shop-api", "/home/dev/shop-api", 200L), sessions[0])
        assertEquals(OcSession("ses_2", null, "blog", "/home/dev/blog", 0L), sessions[1])
        val recorded = server.takeRequest()
        assertEquals("/session", recorded.requestUrl!!.encodedPath)
        assertEquals("/home/dev/shop-api", recorded.requestUrl!!.queryParameter("directory"))
    }

    @Test
    fun `last message comes from final array element with text`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            [
              { "info": { "role": "user" }, "parts": [ { "type": "text", "text": "hi" } ] },
              { "info": { "role": "assistant" }, "parts": [ { "type": "text", "text": "all done" } ] }
            ]
            """.trimIndent()
        ))
        assertEquals(SessionMessage(false, "all done"), client().getLastMessage("ses_1", "/home/dev/shop-api"))
        val recorded = server.takeRequest()
        assertEquals("/session/ses_1/message", recorded.requestUrl!!.encodedPath)
        assertEquals("/home/dev/shop-api", recorded.requestUrl!!.queryParameter("directory"))
    }

    @Test
    fun `last message null when nothing readable`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(client().getLastMessage("ses_1", null))
    }

    @Test
    fun `send prompt posts text parts and reports success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(client().sendPrompt("ses_1", "hello from the car", "/home/dev/shop-api"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/session/ses_1/message", recorded.requestUrl!!.encodedPath)
        assertEquals("/home/dev/shop-api", recorded.requestUrl!!.queryParameter("directory"))
        assertTrue(recorded.body.readUtf8().contains("hello from the car"))
    }

    @Test
    fun `send prompt false on server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(client().sendPrompt("ses_1", "x", null))
    }

    @Test
    fun `pending permissions map id and label filtered by session id`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            [
              { "id": "per_1", "sessionID": "ses_1", "permission": "bash", "patterns": ["rm -rf build/"] },
              { "id": "per_9", "sessionID": "ses_other", "permission": "bash", "patterns": ["other"] },
              { "id": "per_2", "sessionID": "ses_1", "permission": "edit", "patterns": [] }
            ]
            """.trimIndent()
        ))
        val pending = client().listPendingPermissions("ses_1", "/home/dev/shop-api")
        assertEquals(listOf(PendingPermission("per_1", "bash: rm -rf build/"), PendingPermission("per_2", "edit")), pending)
        val recorded = server.takeRequest()
        assertEquals("/permission", recorded.requestUrl!!.encodedPath)
        assertEquals("/home/dev/shop-api", recorded.requestUrl!!.queryParameter("directory"))
    }

    @Test
    fun `pending permission label joins all patterns`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            [
              { "id": "per_1", "sessionID": "ses_1", "permission": "bash", "patterns": ["rm -rf build/", "echo hi"] }
            ]
            """.trimIndent()
        ))
        val pending = client().listPendingPermissions("ses_1", null)
        assertEquals(listOf(PendingPermission("per_1", "bash: rm -rf build/, echo hi")), pending)
    }

    @Test
    fun `pending permissions empty when none`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(emptyList<PendingPermission>(), client().listPendingPermissions("ses_1", null))
    }

    @Test
    fun `pending permissions empty on http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(emptyList<PendingPermission>(), client().listPendingPermissions("ses_1", null))
    }
}
