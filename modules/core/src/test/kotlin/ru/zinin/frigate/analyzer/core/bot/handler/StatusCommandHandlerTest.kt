package ru.zinin.frigate.analyzer.core.bot.handler

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.service.StatusService
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.impl.StatusMessageFormatter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StatusCommandHandlerTest {
    private val statusService = mockk<StatusService>()
    private val formatter = mockk<StatusMessageFormatter>()
    private val userService = mockk<TelegramUserService>()
    private val clock = Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC)
    private val handler = StatusCommandHandler(statusService, formatter, userService, clock)

    @Test
    fun `handler has correct command metadata`() {
        assertThat(handler.command).isEqualTo("status")
        assertThat(handler.requiredRole).isEqualTo(UserRole.OWNER)
        assertThat(handler.ownerOnly).isTrue()
        assertThat(handler.order).isEqualTo(8)
    }
}
