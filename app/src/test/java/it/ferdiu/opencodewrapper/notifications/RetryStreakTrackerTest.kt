package it.ferdiu.opencodewrapper.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryStreakTrackerTest {

    @Test
    fun `attempts below threshold never notify`() {
        val tracker = RetryStreakTracker()
        assertFalse(tracker.shouldNotify("ses_1", 1))
        assertFalse(tracker.shouldNotify("ses_1", 2))
    }

    @Test
    fun `first attempt at or above threshold notifies once`() {
        val tracker = RetryStreakTracker()
        assertTrue(tracker.shouldNotify("ses_1", 3))
        assertFalse(tracker.shouldNotify("ses_1", 4))  // same streak: no repeat
        assertFalse(tracker.shouldNotify("ses_1", 10))
    }

    @Test
    fun `sessions are tracked independently`() {
        val tracker = RetryStreakTracker()
        assertTrue(tracker.shouldNotify("ses_1", 3))
        assertTrue(tracker.shouldNotify("ses_2", 5))
    }

    @Test
    fun `reset starts a new streak`() {
        val tracker = RetryStreakTracker()
        assertTrue(tracker.shouldNotify("ses_1", 3))
        tracker.reset("ses_1")
        assertFalse(tracker.shouldNotify("ses_1", 2))  // new streak below threshold
        assertTrue(tracker.shouldNotify("ses_1", 3))   // new streak at threshold notifies again
    }
}
