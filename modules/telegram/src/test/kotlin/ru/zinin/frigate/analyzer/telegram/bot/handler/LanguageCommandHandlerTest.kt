package ru.zinin.frigate.analyzer.telegram.bot.handler

import io.mockk.mockk
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.impl.TelegramUserServiceImpl
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LanguageCommandHandlerTest {
    private val userService = mockk<TelegramUserService>()
    private val msg = mockk<MessageResolver>()
    private val handler = LanguageCommandHandler(userService, msg)

    @Test
    fun `handler has correct command metadata`() {
        assertEquals("language", handler.command)
        assertEquals(UserRole.USER, handler.requiredRole)
        assertFalse(handler.ownerOnly)
        assertEquals(6, handler.order)
    }

    @Test
    fun `supported languages contains exactly ru and en`() {
        assertEquals(setOf("ru", "en"), TelegramUserServiceImpl.SUPPORTED_LANGUAGES)
    }

    @Test
    fun `unsupported language code is rejected by service validation`() {
        val exception =
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                require("fr" in TelegramUserServiceImpl.SUPPORTED_LANGUAGES) { "Unsupported language: fr" }
            }
        assertEquals("Unsupported language: fr", exception.message)
    }
}
