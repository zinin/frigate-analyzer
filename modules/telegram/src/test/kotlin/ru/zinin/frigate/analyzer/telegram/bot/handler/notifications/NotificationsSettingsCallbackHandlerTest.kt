package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class NotificationsSettingsCallbackHandlerTest {
    private val userService = mockk<TelegramUserService>(relaxed = true)
    private val appSettings = mockk<AppSettingsService>(relaxed = true)

    private val handler = NotificationsSettingsCallbackHandler(
        userService = userService,
        appSettings = appSettings,
        renderer = mockk(relaxed = true),
    )

    private val chatId = 100L

    private fun user(
        recording: Boolean = true,
        signal: Boolean = true,
    ): TelegramUserDto = TelegramUserDto(
        id = UUID.randomUUID(),
        username = "alice",
        chatId = chatId,
        userId = 1L,
        firstName = null, lastName = null,
        status = UserStatus.ACTIVE,
        creationTimestamp = Instant.EPOCH,
        activationTimestamp = Instant.EPOCH,
        languageCode = "en",
        notificationsRecordingEnabled = recording,
        notificationsSignalEnabled = signal,
    )

    @Test
    fun `nfs u rec 0 disables per-user recording flag`() = runTest {
        val current = user(recording = true)
        coEvery { userService.findByChatIdAsDto(chatId) } returns current

        val result = handler.dispatch("nfs:u:rec:0", chatId, isOwner = false, current)

        coVerify(exactly = 1) { userService.updateNotificationsRecordingEnabled(chatId, false) }
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER, result)
    }

    @Test
    fun `nfs u sig 1 enables per-user signal flag`() = runTest {
        val current = user(signal = false)
        val result = handler.dispatch("nfs:u:sig:1", chatId, isOwner = false, current)

        coVerify(exactly = 1) { userService.updateNotificationsSignalEnabled(chatId, true) }
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER, result)
    }

    @Test
    fun `nfs g rec 0 rejected for non-owner`() = runTest {
        val current = user()
        val result = handler.dispatch("nfs:g:rec:0", chatId, isOwner = false, current)

        coVerify(exactly = 0) { appSettings.setBoolean(any(), any(), any()) }
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.UNAUTHORIZED, result)
    }

    @Test
    fun `nfs g rec 0 disables global recording flag for owner`() = runTest {
        val current = user()
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true

        val result = handler.dispatch("nfs:g:rec:0", chatId, isOwner = true, current)

        coVerify(exactly = 1) {
            appSettings.setBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, false, "alice")
        }
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER, result)
    }

    @Test
    fun `nfs g sig 1 enables global signal flag for owner`() = runTest {
        val current = user()
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } returns false

        val result = handler.dispatch("nfs:g:sig:1", chatId, isOwner = true, current)

        coVerify(exactly = 1) {
            appSettings.setBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true, "alice")
        }
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER, result)
    }

    @Test
    fun `nfs close returns CLOSE`() = runTest {
        val result = handler.dispatch("nfs:close", chatId, isOwner = false, user())
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.CLOSE, result)
    }

    @Test
    fun `unknown nfs callback returns IGNORE`() = runTest {
        val result = handler.dispatch("nfs:unknown", chatId, isOwner = true, user())
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.IGNORE, result)
    }
}
