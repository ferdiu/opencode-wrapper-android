package it.ferdiu.opencodewrapper.notifications

import it.ferdiu.opencodewrapper.api.PermissionDecision
import java.util.Locale

/**
 * Maps a free-form voice transcript (Android Auto notification reply) to a
 * permission decision. Word-boundary matching, case-insensitive.
 *
 * Precedence: "always" > deny > allow. Deny beats allow so that negations
 * like "don't allow" (which contains the allow keyword) reject instead of
 * approving.
 */
object PermissionReplyParser {

    private val ALWAYS = Regex("\\balways\\b", RegexOption.IGNORE_CASE)
    private val DENY = Regex(
        "\\b(deny|denied|no|not|nope|reject|refuse|block|cancel|don'?t|never)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ALLOW = Regex(
        "\\b(allow|yes|yeah|yep|approve|ok|okay|accept|grant|sure)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun parse(transcript: String?): PermissionDecision? {
        val text = transcript?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (text.isEmpty()) return null
        if (ALWAYS.containsMatchIn(text)) return PermissionDecision.ALWAYS
        if (DENY.containsMatchIn(text)) return PermissionDecision.REJECT
        if (ALLOW.containsMatchIn(text)) return PermissionDecision.ONCE
        return null
    }
}
