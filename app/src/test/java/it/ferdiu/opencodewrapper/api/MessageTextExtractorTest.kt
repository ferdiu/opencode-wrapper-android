package it.ferdiu.opencodewrapper.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextExtractorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun extract(payload: String): SessionMessage? =
        MessageTextExtractor.lastReadableMessage(json.parseToJsonElement(payload).jsonArray)

    @Test
    fun `last assistant text message is extracted with author`() {
        val msg = extract(
            """
            [
              { "info": { "role": "user" }, "parts": [ { "type": "text", "text": "fix the bug" } ] },
              { "info": { "role": "assistant" }, "parts": [ { "type": "text", "text": "Done, tests pass." } ] }
            ]
            """.trimIndent()
        )
        assertEquals(SessionMessage(isFromUser = false, text = "Done, tests pass."), msg)
    }

    @Test
    fun `last message from user is marked as from user`() {
        val msg = extract(
            """
            [
              { "info": { "role": "assistant" }, "parts": [ { "type": "text", "text": "Working on it." } ] },
              { "info": { "role": "user" }, "parts": [ { "type": "text", "text": "hurry up" } ] }
            ]
            """.trimIndent()
        )
        assertTrue(msg!!.isFromUser)
        assertEquals("hurry up", msg.text)
    }

    @Test
    fun `non-text parts are skipped but message still readable`() {
        val msg = extract(
            """
            [
              { "info": { "role": "assistant" }, "parts": [
                  { "type": "tool", "tool": "bash", "state": {} },
                  { "type": "text", "text": "Ran the command." }
              ] }
            ]
            """.trimIndent()
        )
        assertEquals("Ran the command.", msg!!.text)
    }

    @Test
    fun `messages without text parts are skipped in favor of earlier ones`() {
        val msg = extract(
            """
            [
              { "info": { "role": "assistant" }, "parts": [ { "type": "text", "text": "Earlier answer." } ] },
              { "info": { "role": "assistant" }, "parts": [ { "type": "tool", "tool": "bash", "state": {} } ] }
            ]
            """.trimIndent()
        )
        assertEquals("Earlier answer.", msg!!.text)
    }

    @Test
    fun `empty array and all-empty messages return null`() {
        assertNull(extract("[]"))
        assertNull(extract("""[ { "info": { "role": "assistant" }, "parts": [ { "type": "text", "text": "  " } ] } ]"""))
    }

    @Test
    fun `multiple text parts are joined with newlines`() {
        val msg = extract(
            """
            [
              { "info": { "role": "assistant" }, "parts": [
                  { "type": "text", "text": "First." },
                  { "type": "text", "text": "Second." }
              ] }
            ]
            """.trimIndent()
        )
        assertEquals("First.\nSecond.", msg!!.text)
    }
}
