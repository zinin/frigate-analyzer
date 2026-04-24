package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider

class AiDescriptionTelegramGuardTest {
    @Test
    fun `validate passes when TelegramBot bean is available`() {
        val bot = mockk<TelegramBot>(relaxed = true)
        val provider =
            mockk<ObjectProvider<TelegramBot>> {
                every { getIfAvailable() } returns bot
            }

        AiDescriptionTelegramGuard(provider).validate()
    }

    @Test
    fun `validate throws with actionable message when TelegramBot is missing`() {
        val provider =
            mockk<ObjectProvider<TelegramBot>> {
                every { getIfAvailable() } returns null
            }

        val ex =
            assertThrows<IllegalStateException> {
                AiDescriptionTelegramGuard(provider).validate()
            }

        // Fail-fast message must name both flags so the operator can flip one of them without digging.
        assertEquals(true, ex.message?.contains("application.ai.description.enabled=true"))
        assertEquals(true, ex.message?.contains("application.telegram.enabled=true"))
        assertEquals(true, ex.message?.contains("TELEGRAM_ENABLED=true"))
        assertEquals(true, ex.message?.contains("APP_AI_DESCRIPTION_ENABLED=false"))
    }
}
