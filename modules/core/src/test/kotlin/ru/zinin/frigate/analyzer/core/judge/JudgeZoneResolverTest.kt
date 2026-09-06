package ru.zinin.frigate.analyzer.core.judge

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class JudgeZoneResolverTest {
    private val userService = mockk<TelegramUserService>()
    private val telegramProperties = TelegramProperties(enabled = true, botToken = "t", owner = "owner")

    private fun resolver(zone: String) = JudgeZoneResolver(JudgeProperties(zone = zone), userService, telegramProperties)

    @Test
    fun `explicit zone wins`() = runTest { assertEquals(ZoneId.of("Asia/Tomsk"), resolver("Asia/Tomsk").resolve()) }

    @Test
    fun `falls back to the owner zone`() =
        runTest {
            coEvery { userService.findByUsernameIgnoreCase("owner") } returns mockk { every { chatId } returns 42L }
            coEvery { userService.getUserZone(42L) } returns ZoneId.of("Europe/Moscow")
            assertEquals(ZoneId.of("Europe/Moscow"), resolver("").resolve())
        }

    @Test
    fun `owner without chat or a failing lookup falls back to the JVM zone`() =
        runTest {
            coEvery { userService.findByUsernameIgnoreCase("owner") } returns null
            assertEquals(ZoneId.systemDefault(), resolver("").resolve())
            coEvery { userService.findByUsernameIgnoreCase("owner") } throws IllegalStateException("db down")
            assertEquals(ZoneId.systemDefault(), resolver("").resolve())
        }
}
