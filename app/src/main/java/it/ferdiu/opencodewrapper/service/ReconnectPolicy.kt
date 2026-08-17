package it.ferdiu.opencodewrapper.service

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with jitter, capped, for the SSE reconnect loop.
 * Not thread-safe by design - only used from the service's single collector
 * coroutine.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 60_000,
) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        val exp = baseDelayMs * (1L shl min(attempt, 10))
        val capped = min(exp, maxDelayMs)
        attempt++
        // +/- 20% jitter so many clients reconnecting after a server restart
        // don't all hammer it in lockstep.
        val jitter = (capped * 0.2 * (Random.nextDouble() * 2 - 1)).toLong()
        return (capped + jitter).coerceAtLeast(baseDelayMs)
    }

    fun reset() {
        attempt = 0
    }

    val currentAttempt: Int get() = attempt
}
