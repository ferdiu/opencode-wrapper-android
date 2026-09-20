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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenCodeClientV2ReplyTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun client(): OpenCodeClientV2 =
        OpenCodeClientV2(ServerConfig(baseUrl = server.url("/").toString().trimEnd('/'), authHeaderValue = null))

    @Test
    fun `reply permission posts decision to the reply endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val ok = client().replyPermission("ses_1", "per_1", PermissionDecision.ALWAYS)

        assertTrue(ok)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        // Verified shape (Task 1): global legacy endpoint, field name "reply".
        assertEquals("/permission/per_1/reply", recorded.path)
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("always", body["reply"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reply permission returns false on http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(client().replyPermission("ses_1", "per_x", PermissionDecision.REJECT))
    }

    @Test
    fun `reply question posts the answer to the reply endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val ok = client().replyQuestion("ses_1", "req_1", "the blue one")

        assertTrue(ok)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        // Verified shape (Task 1): global legacy endpoint.
        assertEquals("/question/req_1/reply", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("the blue one"))
    }

    @Test
    fun `reply question returns false on http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        assertFalse(client().replyQuestion("ses_1", "req_x", "answer"))
    }
}
