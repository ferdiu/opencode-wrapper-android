package it.ferdiu.opencodewrapper.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser regression tests using payloads shaped like the real OpenCode v2
 * event schema (packages/sdk/js/src/v2/gen/types.gen.ts):
 *  - permission.asked: properties = { id, sessionID, permission, patterns, ... }
 *  - question.asked:   properties = { id, sessionID, questions: [{ question, header, ... }] }
 */
class OcEventParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(payload: String): OcEvent =
        OcEventParser.parse(json.parseToJsonElement(payload).jsonObject)

    @Test
    fun `permission asked with v2 schema parses with permission label`() {
        val event = parse(
            """
            {
              "id": "evt-1",
              "type": "permission.asked",
              "properties": {
                "id": "per_123",
                "sessionID": "ses_456",
                "permission": "bash",
                "patterns": ["rm -rf *"],
                "metadata": {},
                "always": []
              }
            }
            """.trimIndent()
        )
        assertTrue(event is OcEvent.PermissionAsked)
        event as OcEvent.PermissionAsked
        assertEquals("per_123", event.permissionId)
        assertEquals("ses_456", event.sessionId)
        assertEquals("bash: rm -rf *", event.title)
    }

    @Test
    fun `permission asked without patterns falls back to permission name`() {
        val event = parse(
            """
            {
              "type": "permission.asked",
              "properties": {
                "id": "per_1",
                "sessionID": "ses_1",
                "permission": "edit",
                "patterns": [],
                "metadata": {},
                "always": []
              }
            }
            """.trimIndent()
        )
        assertTrue(event is OcEvent.PermissionAsked)
        assertEquals("edit", (event as OcEvent.PermissionAsked).title)
    }

    @Test
    fun `question asked with v2 schema parses id and question text`() {
        val event = parse(
            """
            {
              "type": "question.asked",
              "properties": {
                "id": "q_1",
                "sessionID": "ses_9",
                "questions": [
                  {
                    "question": "Which database should I use?",
                    "header": "Database",
                    "options": []
                  }
                ]
              }
            }
            """.trimIndent()
        )
        assertTrue(event is OcEvent.QuestionAsked)
        event as OcEvent.QuestionAsked
        assertEquals("q_1", event.requestId)
        assertEquals("ses_9", event.sessionId)
        assertEquals("Which database should I use?", event.prompt)
    }

    @Test
    fun `question asked with legacy requestID and prompt still parses`() {
        val event = parse(
            """
            {
              "type": "question.asked",
              "properties": {
                "requestID": "q_old",
                "sessionID": "ses_2",
                "prompt": "Proceed?"
              }
            }
            """.trimIndent()
        )
        assertTrue(event is OcEvent.QuestionAsked)
        event as OcEvent.QuestionAsked
        assertEquals("q_old", event.requestId)
        assertEquals("Proceed?", event.prompt)
    }

    @Test
    fun `session status busy and idle parse`() {
        val busy = parse(
            """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"busy"}}}"""
        )
        assertEquals(OcEvent.SessionBusy("ses_1"), busy)

        val idle = parse(
            """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"idle"}}}"""
        )
        assertEquals(OcEvent.SessionIdle("ses_1"), idle)
    }

    @Test
    fun `unrecognized event type becomes Unknown`() {
        val event = parse("""{"type":"pty.created","properties":{"info":{}}}""")
        assertTrue(event is OcEvent.Unknown)
    }
}
