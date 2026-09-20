package it.ferdiu.opencodewrapper.notifications

/**
 * Tracks per-session retry streaks so the router can surface *persistent*
 * retry loops (model down, auth broken) exactly once per streak, while
 * transient retries stay silent per the project brief.
 *
 * In-memory only: a process restart simply re-arms the tracker, which is
 * acceptable — worst case one repeated warning.
 */
class RetryStreakTracker(
    private val threshold: Int = DEFAULT_THRESHOLD,
) {

    private val notified = mutableSetOf<String>()

    /** Returns true exactly once per streak: on the first call at or above
     *  [threshold] since the last [reset] for this session. */
    fun shouldNotify(sessionId: String, attempt: Int): Boolean {
        if (attempt < threshold) return false
        return notified.add(sessionId)
    }

    /** Call when the session leaves the retry state (busy/idle/recovered). */
    fun reset(sessionId: String) {
        notified.remove(sessionId)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 3
    }
}
