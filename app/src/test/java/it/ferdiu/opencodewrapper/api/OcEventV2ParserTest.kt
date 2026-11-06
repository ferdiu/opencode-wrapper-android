package it.ferdiu.opencodewrapper.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests for the OpenCode 2.x event envelope (confirmed against
 * upstream v2.2.5 source): each SSE payload is a flat object
 * { id, created, type, location?, data }.
 */
class OcEventV2ParserTest {

    private fun parseV2Event(payload: String, fallbackDirectory: String? = null): OcEnvelope? =
        OcEventParser.parseV2Event(payload, fallbackDirectory)

    @Test
    fun `permission asked maps action and resources to label`() {
        val envelope = parseV2Event(
            """
            {
              "id": "evt_1",
              "created": 1720000000000,
              "type": "permission.asked",
              "location": { "directory": "/home/dev/shop-api" },
              "data": {
                "id": "per_123",
                "sessionID": "ses_456",
                "action": "bash",
                "resources": ["rm -rf build/", "echo hi"],
                "save": []
              }
            }
            """.trimIndent()
        )!!

        assertEquals("/home/dev/shop-api", envelope.directory)
        val event = envelope.event as OcEvent.PermissionAsked
        assertEquals("per_123", event.permissionId)
        assertEquals("ses_456", event.sessionId)
        assertEquals("bash: rm -rf build/, echo hi", event.title)
    }

    @Test
    fun `permission replied settles the notification`() {
        val envelope = parseV2Event(
            """
            {
              "id": "evt_2",
              "created": 1720000000001,
              "type": "permission.replied",
              "data": { "sessionID": "ses_456", "requestID": "per_123", "reply": "once" }
            }
            """.trimIndent()
        )!!

        val event = envelope.event as OcEvent.PermissionReplied
        assertEquals("per_123", event.permissionId)
        assertEquals("ses_456", event.sessionId)
    }

    @Test
    fun `form created maps to question asked with field description as prompt`() {
        val envelope = parseV2Event(
            """
            {
              "id": "evt_3",
              "created": 1720000000002,
              "type": "form.created",
              "location": { "directory": "/home/dev/blog" },
              "data": {
                "form": {
                  "id": "frm_1",
                  "sessionID": "ses_9",
                  "title": "Questions",
                  "fields": [
                    { "key": "q0", "type": "string", "title": "Pick a color", "description": "Which color?" }
                  ]
                }
              }
            }
            """.trimIndent()
        )!!

        assertEquals("/home/dev/blog", envelope.directory)
        val event = envelope.event as OcEvent.QuestionAsked
        assertEquals("frm_1", event.requestId)
        assertEquals("ses_9", event.sessionId)
        assertEquals("Questions", event.prompt)
    }

    @Test
    fun `form replied and cancelled settle the question`() {
        for (type in listOf("form.replied", "form.cancelled")) {
            val envelope = parseV2Event(
                """
                {
                  "id": "evt_4",
                  "created": 1720000000003,
                  "type": "$type",
                  "data": { "id": "frm_1", "sessionID": "ses_9", "answer": { "q0": "blue" } }
                }
                """.trimIndent()
            )!!
            val event = envelope.event as OcEvent.QuestionSettled
            assertEquals("frm_1", event.requestId)
            assertEquals("ses_9", event.sessionId)
        }
    }

    @Test
    fun `execution lifecycle maps to busy and idle`() {
        val busy = parseV2Event(
            """
            { "id": "evt_5", "created": 1, "type": "session.execution.started", "data": { "sessionID": "ses_1" } }
            """.trimIndent()
        )!!.event as OcEvent.SessionBusy
        assertEquals("ses_1", busy.sessionId)

        val idle = parseV2Event(
            """
            { "id": "evt_6", "created": 2, "type": "session.execution.succeeded", "data": { "sessionID": "ses_1" } }
            """.trimIndent()
        )!!.event as OcEvent.SessionIdle
        assertEquals("ses_1", idle.sessionId)

        val interrupted = parseV2Event(
            """
            { "id": "evt_7", "created": 3, "type": "session.execution.interrupted",
              "data": { "sessionID": "ses_1", "reason": "user" } }
            """.trimIndent()
        )!!.event as OcEvent.SessionIdle
        assertEquals("ses_1", interrupted.sessionId)
    }

    @Test
    fun `execution and step failed map to session error`() {
        for (type in listOf("session.execution.failed", "session.step.failed")) {
            val event = parseV2Event(
                """
                { "id": "evt_8", "created": 4, "type": "$type",
                  "data": { "sessionID": "ses_1", "error": { "type": "LLMError", "message": "model exploded" } } }
                """.trimIndent()
            )!!.event as OcEvent.SessionError
            assertEquals("ses_1", event.sessionId)
            assertEquals("model exploded", event.message)
        }
    }

    @Test
    fun `session created renamed and deleted map to session events`() {
        val created = parseV2Event(
            """
            { "id": "evt_9", "created": 5, "type": "session.created",
              "data": { "sessionID": "ses_2", "title": "Fix login" } }
            """.trimIndent()
        )!!.event as OcEvent.SessionUpdated
        assertEquals("ses_2", created.sessionId)
        assertEquals("Fix login", created.title)

        val renamed = parseV2Event(
            """
            { "id": "evt_10", "created": 6, "type": "session.renamed",
              "data": { "sessionID": "ses_2", "title": "Fix login bug" } }
            """.trimIndent()
        )!!.event as OcEvent.SessionUpdated
        assertEquals("Fix login bug", renamed.title)

        val deleted = parseV2Event(
            """
            { "id": "evt_11", "created": 7, "type": "session.deleted", "data": { "sessionID": "ses_2" } }
            """.trimIndent()
        )!!.event as OcEvent.SessionDeleted
        assertEquals("ses_2", deleted.sessionId)
    }

    @Test
    fun `durable envelope fields are tolerated and directory falls back`() {
        val envelope = parseV2Event(
            """
            {
              "id": "evt_12",
              "created": 8,
              "type": "session.execution.succeeded",
              "durable": { "aggregateID": "ses_3", "seq": 42, "version": 1 },
              "metadata": { "source": "test" },
              "data": { "sessionID": "ses_3" }
            }
            """.trimIndent(),
            fallbackDirectory = "/fallback/dir",
        )!!

        val event = envelope.event as OcEvent.SessionIdle
        assertEquals("ses_3", event.sessionId)
        // location absent -> fallbackDirectory is used
        assertEquals("/fallback/dir", envelope.directory)
    }

    @Test
    fun `unknown type degrades to harmless Unknown`() {
        val envelope = parseV2Event(
            """
            { "id": "evt_13", "created": 9, "type": "skill.updated", "data": { "id": "sk_1" } }
            """.trimIndent()
        )!!

        val event = envelope.event as OcEvent.Unknown
        assertEquals("skill.updated", event.type)
    }

    @Test
    fun `malformed payload returns null`() {
        assertNull(parseV2Event("not json"))
        assertNull(parseV2Event(""))
    }
}
