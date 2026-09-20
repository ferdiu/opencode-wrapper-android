package it.ferdiu.opencodewrapper.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Extracts the last message worth showing/reading from a session's message
 * list. "Worth showing" = has at least one non-blank text part. Tool calls,
 * thinking blocks, and blank messages are skipped by walking backwards.
 *
 * Handles the v1 shape [{ info: { role }, parts: [...] }] and tolerates
 * unwrapped messages. Assumes oldest-first ordering (verified against the
 * live server).
 */
object MessageTextExtractor {

    fun lastReadableMessage(messages: JsonArray): SessionMessage? {
        for (i in messages.size - 1 downTo 0) {
            val obj = messages[i] as? JsonObject ?: continue
            val info = (obj["info"] as? JsonObject) ?: obj
            val parts = (obj["parts"] as? JsonArray) ?: (info["parts"] as? JsonArray) ?: continue

            val text = parts.mapNotNull { part ->
                val p = part as? JsonObject ?: return@mapNotNull null
                val type = (p["type"] as? JsonPrimitive)?.contentOrNull()
                if (type != null && type != "text") return@mapNotNull null
                (p["text"] as? JsonPrimitive)?.contentOrNull()
            }.filter { it.isNotBlank() }.joinToString("\n")

            if (text.isNotBlank()) {
                val role = (info["role"] as? JsonPrimitive)?.contentOrNull()
                    ?: (info["type"] as? JsonPrimitive)?.contentOrNull()
                return SessionMessage(isFromUser = role == "user", text = text)
            }
        }
        return null
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
}
