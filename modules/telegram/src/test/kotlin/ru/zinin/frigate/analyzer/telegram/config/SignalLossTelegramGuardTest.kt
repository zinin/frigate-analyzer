package ru.zinin.frigate.analyzer.telegram.config

import dev.inmo.tgbotapi.bot.TelegramBot
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider

class SignalLossTelegramGuardTest {
    @Test
    fun `validate passes when TelegramBot bean is available`() {
        val bot = mockk<TelegramBot>(relaxed = true)
        val provider =
            mockk<ObjectProvider<TelegramBot>> {
                every { getIfAvailable() } returns bot
            }

        SignalLossTelegramGuard(provider).validate()
    }

    @Test
    fun `validate throws with actionable message when TelegramBot is missing`() {
        val provider =
            mockk<ObjectProvider<TelegramBot>> {
                every { getIfAvailable() } returns null
            }

        val ex =
            assertThrows<IllegalStateException> {
                SignalLossTelegramGuard(provider).validate()
            }

        // Fail-fast message must name both flags and both env-var aliases so the operator can
        // flip whichever one they prefer without consulting docs.
        assertEquals(true, ex.message?.contains("application.signal-loss.enabled=true"))
        assertEquals(true, ex.message?.contains("application.telegram.enabled=true"))
        assertEquals(true, ex.message?.contains("TELEGRAM_ENABLED=true"))
        assertEquals(true, ex.message?.contains("SIGNAL_LOSS_ENABLED=false"))
    }
}
