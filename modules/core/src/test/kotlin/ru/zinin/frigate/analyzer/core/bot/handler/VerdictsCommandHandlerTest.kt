package ru.zinin.frigate.analyzer.core.bot.handler

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.service.NotificationVerdictService
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

class VerdictsCommandHandlerTest {
    private val verdictService = mockk<NotificationVerdictService>()
    private val formatter = mockk<VerdictsMessageFormatter>()
    private val userService = mockk<TelegramUserService>()
    private val msg = mockk<MessageResolver>()
    private val handler = VerdictsCommandHandler(verdictService, formatter, userService, msg)

    @Test
    fun `handler has correct command metadata`() {
        assertThat(handler.command).isEqualTo("verdicts")
        assertThat(handler.requiredRole).isEqualTo(UserRole.OWNER)
        assertThat(handler.ownerOnly).isTrue()
        assertThat(handler.order).isEqualTo(10)
    }
}
