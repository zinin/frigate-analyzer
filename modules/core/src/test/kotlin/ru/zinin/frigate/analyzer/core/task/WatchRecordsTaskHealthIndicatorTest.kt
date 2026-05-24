package ru.zinin.frigate.analyzer.core.task

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.Status
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WatchRecordsTaskHealthIndicatorTest {
    private val task = mockk<WatchRecordsTask>()
    private val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)
    private val indicator = WatchRecordsTaskHealthIndicator(task, clock)

    @Test
    fun `health delegates to computeHealth`() {
        val expectedHealth =
            Health
                .Builder()
                .up()
                .withDetail("reason", "starting up")
                .build()
        every { task.computeHealth(Instant.parse("2026-05-23T12:00:00Z")) } returns expectedHealth

        val actual = indicator.health()

        assertEquals(Status.UP, actual.status)
        assertEquals("starting up", actual.details["reason"])
    }
}
