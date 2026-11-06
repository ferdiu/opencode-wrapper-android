package it.ferdiu.opencodewrapper.api

import it.ferdiu.opencodewrapper.data.ServerConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Wire-contract tests for the OpenCode 2.x client, verified against the
 *  upstream v2.2.5 protocol (packages/protocol/src/groups). */
class OpenCodeClientV2ApiTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun client(): OpenCodeClientV2 =
        OpenCodeClientV2(ServerConfig(baseUrl = server.url("/").toString().trimEnd('/'), authHeaderValue = null))

    @Test
    fun `health check true only on 200 with parseable version`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":"2.2.5","pid":1,"urls":[],"paths":{"tmp":"/t"}}"""))
        assertTrue(client().healthCheck())
        assertEquals("/api/info", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(client().healthCheck())

        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertFalse(client().healthCheck())
    }

    @Test
    fun `session snapshot unwraps data and derives busy from time`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            { "data": { "id": "ses_1", "title": "Fix login", "projectID": "proj_1",
              "location": { "directory": "/home/dev/shop-api" },
              "time": { "created": 1, "updated": 2, "busy": 3 } } }
            """.trimIndent()
        ))

        val snapshot = client().getSessionSnapshot("ses_1")
        assertEquals(SessionSnapshot("ses_1", "Fix login", "busy"), snapshot)
        assertEquals("/api/session/ses_1", server.takeRequest().path)
    }

    @Test
    fun `session snapshot idle when idle is newer than busy`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            { "data": { "id": "ses_1", "projectID": "p",
              "location": { "directory": "/" },
              "time": { "created": 1, "updated": 2, "busy": 3, "idle": 4 } } }
            """.trimIndent()
        ))

        assertEquals("idle", client().getSessionSnapshot("ses_1")!!.statusType)
    }

    @Test
    fun `active sessions map session active keys to busy snapshots`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{ "data": { "ses_1": { "type": "running" }, "ses_2": { "type": "running" } } }"""
        ))

        val active = client().listActiveSessions()
        assertEquals(listOf(SessionSnapshot("ses_1", null, "busy"), SessionSnapshot("ses_2", null, "busy")), active)
        assertEquals("/api/session/active", server.takeRequest().path)
    }

    @Test
    fun `reply permission posts decision to the session scoped route`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val ok = client().replyPermission("ses_1", "per_1", PermissionDecision.ALWAYS, "/home/dev/x")

        assertTrue(ok)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/session/ses_1/permission/per_1/reply", recorded.path)
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("always", body["decision"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reply question resolves the form field key from the form detail`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{ "data": { "id": "frm_1", "sessionID": "ses_1", "title": "Questions",
                "fields": [ { "key": "custom_key", "type": "string", "title": "Pick" } ] } }"""
        ))
        server.enqueue(MockResponse().setResponseCode(204))

        val ok = client().replyQuestion("ses_1", "frm_1", "the blue one", null)

        assertTrue(ok)
        server.takeRequest() // form detail GET
        val recorded = server.takeRequest()
        assertEquals("/api/session/ses_1/form/frm_1/reply", recorded.path)
        assertEquals("""{"answer":{"custom_key":"the blue one"}}""", recorded.body.readUtf8())
    }

    @Test
    fun `reply question falls back to q0 when the form detail is unavailable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404)) // form detail
        server.enqueue(MockResponse().setResponseCode(204))

        val ok = client().replyQuestion("ses_1", "frm_9", "answer", null)

        assertTrue(ok)
        server.takeRequest()
        val recorded = server.takeRequest()
        assertEquals("""{"answer":{"q0":"answer"}}""", recorded.body.readUtf8())
    }

    @Test
    fun `list projects maps canonical and name`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            [
              { "id": "abc", "canonical": "/home/dev/shop-api", "name": "Shop API", "icon": { "color": "mint" }, "time": { "created": 1 }, "sandboxes": [] },
              { "id": "global", "canonical": "/", "time": { "created": 1 }, "sandboxes": [] }
            ]
            """.trimIndent()
        ))

        val projects = client().listProjects()
        assertEquals(
            listOf(
                OcProject("abc", "/home/dev/shop-api", "Shop API", iconColor = "mint"),
                OcProject("global", "/", "Global", iconColor = null),
            ),
            projects,
        )
        assertEquals("/api/project", server.takeRequest().path)
    }

    @Test
    fun `list sessions unwraps data maps location directory and filters noise`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            { "data": [
              { "id": "ses_1", "title": "Fix login", "projectID": "p",
                "location": { "directory": "/home/dev/shop-api" }, "time": { "created": 1, "updated": 200 } },
              { "id": "ses_sub", "parentID": "ses_1", "projectID": "p",
                "location": { "directory": "/home/dev/shop-api" }, "time": { "created": 1, "updated": 2 } },
              { "id": "ses_arch", "projectID": "p",
                "location": { "directory": "/home/dev/shop-api" }, "time": { "created": 1, "updated": 3, "archived": 4 } }
            ], "cursor": {} }
            """.trimIndent()
        ))

        val sessions = client().listSessions(directory = "/home/dev/shop-api")
        assertEquals(listOf(OcSession("ses_1", "Fix login", "shop-api", "/home/dev/shop-api", 200L)), sessions)
        val recorded = server.takeRequest()
        assertEquals("/api/session", recorded.requestUrl!!.encodedPath)
        assertEquals("/home/dev/shop-api", recorded.requestUrl!!.queryParameter("directory"))
        assertEquals("50", recorded.requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun `list session statuses derives busy and idle from time`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            { "data": [
              { "id": "ses_1", "projectID": "p", "location": { "directory": "/" },
                "time": { "created": 1, "updated": 2, "busy": 5 } },
              { "id": "ses_2", "projectID": "p", "location": { "directory": "/" },
                "time": { "created": 1, "updated": 3, "busy": 5, "idle": 6 } }
            ], "cursor": {} }
            """.trimIndent()
        ))

        assertEquals(mapOf("ses_1" to "busy", "ses_2" to "idle"), client().listSessionStatuses("/"))
    }

    @Test
    fun `last message requests ascending order and reads user text`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            { "data": [
              { "id": "msg_1", "type": "user", "text": "hi", "time": { "created": 1 } },
              { "id": "msg_2", "type": "assistant", "agent": "build", "content": [ { "type": "text", "text": "all done" } ],
                "time": { "created": 2 } }
            ], "cursor": {} }
            """.trimIndent()
        ))

        assertEquals(SessionMessage(false, "all done"), client().getLastMessage("ses_1", null))
        val recorded = server.takeRequest()
        assertEquals("/api/session/ses_1/message", recorded.requestUrl!!.encodedPath)
        assertEquals("asc", recorded.requestUrl!!.queryParameter("order"))
    }

    @Test
    fun `send prompt posts plain text to the prompt endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        assertTrue(client().sendPrompt("ses_1", "hello from the car", "/home/dev/x"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/session/ses_1/prompt", recorded.path)
        assertEquals("""{"text":"hello from the car"}""", recorded.body.readUtf8())
    }

    @Test
    fun `pending permissions map action and resources`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            { "data": [
              { "id": "per_1", "sessionID": "ses_1", "action": "bash", "resources": ["rm -rf build/"] },
              { "id": "per_9", "sessionID": "ses_other", "action": "bash", "resources": ["other"] }
            ] }
            """.trimIndent()
        ))

        val pending = client().listPendingPermissions("ses_1", null)
        assertEquals(listOf(PendingPermission("per_1", "bash: rm -rf build/")), pending)
        assertEquals("/api/session/ses_1/permission", server.takeRequest().path)
    }

    @Test
    fun `pending permissions empty on http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(emptyList<PendingPermission>(), client().listPendingPermissions("ses_1", null))
    }

    @Test
    fun `snapshot null on http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client().getSessionSnapshot("ses_x"))
    }
}
