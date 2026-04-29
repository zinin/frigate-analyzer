package ru.zinin.frigate.analyzer.core.task

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.service.ObjectTrackerService

class ObjectTracksCleanupTaskTest {
    private val tracker = mockk<ObjectTrackerService>()
    private val task = ObjectTracksCleanupTask(tracker)

    @Test
    fun `cleanup invokes tracker cleanupExpired once`() {
        coEvery { tracker.cleanupExpired() } returns 5L

        task.cleanup()

        coVerify(exactly = 1) { tracker.cleanupExpired() }
    }

    @Test
    fun `cleanup swallows tracker exception and logs WARN (does not propagate)`() {
        coEvery { tracker.cleanupExpired() } throws RuntimeException("db down")

        // Must not throw; otherwise the @Scheduled subsystem would back off
        // on the next firing.
        task.cleanup()

        coVerify(exactly = 1) { tracker.cleanupExpired() }
    }
}
