package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveZoneInputTrackerTest {
    private val tracker = ActiveZoneInputTracker()

    @Test
    fun `tryAcquire returns true for first call`() {
        assertTrue(tracker.tryAcquire(123L))
    }

    @Test
    fun `tryAcquire returns false for second call with same chatId`() {
        tracker.tryAcquire(123L)
        assertFalse(tracker.tryAcquire(123L))
    }

    @Test
    fun `tryAcquire returns true for different chatIds`() {
        assertTrue(tracker.tryAcquire(123L))
        assertTrue(tracker.tryAcquire(456L))
    }

    @Test
    fun `release allows re-acquire`() {
        tracker.tryAcquire(123L)
        tracker.release(123L)
        assertTrue(tracker.tryAcquire(123L))
    }
}
