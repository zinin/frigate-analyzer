package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.Status
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TelegramBotSupervisorHealthIndicatorTest {
    private val supervisor = mockk<TelegramBotSupervisor>()
    private val now = Instant.parse("2026-05-27T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val indicator = TelegramBotSupervisorHealthIndicator(supervisor, clock)

    @Test
    fun `health delegates to supervisor computeHealth with current instant`() {
        val expected =
            Health
                .Builder()
                .up()
                .withDetail("reason", "healthy")
                .build()
        every { supervisor.computeHealth(now) } returns expected

        val actual = indicator.health()

        assertEquals(Status.UP, actual.status)
        assertEquals("healthy", actual.details["reason"])
    }
}
