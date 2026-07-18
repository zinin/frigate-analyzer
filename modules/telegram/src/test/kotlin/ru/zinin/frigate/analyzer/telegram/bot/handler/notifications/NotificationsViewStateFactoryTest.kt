package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationsViewStateFactoryTest {
    private val appSettings = mockk<AppSettingsService>()
    private val scheduleService = mockk<NotificationScheduleService>()
    private val factory = NotificationsViewStateFactory(appSettings, scheduleService)

    private fun user(languageCode: String? = "en") =
        TelegramUserDto(
            id = UUID.randomUUID(),
            username = "alice",
            chatId = 100L,
            userId = 1L,
            firstName = null,
            lastName = null,
            status = UserStatus.ACTIVE,
            creationTimestamp = Instant.EPOCH,
            activationTimestamp = Instant.EPOCH,
            languageCode = languageCode,
            notificationsRecordingEnabled = true,
            notificationsSignalEnabled = false,
        )

    @Test
    fun `non-owner state has null globals and null schedule fields, no settings reads`() =
        runTest {
            val state = factory.build(user(), isOwner = false)

            assertFalse(state.isOwner)
            assertTrue(state.recordingUserEnabled)
            assertFalse(state.signalUserEnabled)
            assertNull(state.recordingGlobalEnabled)
            assertNull(state.signalGlobalEnabled)
            assertNull(state.scheduleEnabled)
            assertNull(state.scheduleWindow)
            assertNull(state.scheduleZone)
            assertEquals("en", state.language)
            coVerify(exactly = 0) { appSettings.getBoolean(any(), any()) }
            coVerify(exactly = 0) { scheduleService.isEnabled() }
        }

    @Test
    fun `owner state carries globals and schedule fields`() =
        runTest {
            coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } returns false
            coEvery { scheduleService.isEnabled() } returns true
            coEvery { scheduleService.getWindow() } returns ScheduleWindow.ofHours(0, 7)
            coEvery { scheduleService.getZone() } returns ZoneId.of("Europe/Moscow")

            val state = factory.build(user(), isOwner = true)

            assertTrue(state.isOwner)
            assertEquals(true, state.recordingGlobalEnabled)
            assertEquals(false, state.signalGlobalEnabled)
            assertEquals(true, state.scheduleEnabled)
            assertEquals("00:00–07:00", state.scheduleWindow)
            assertEquals("Europe/Moscow", state.scheduleZone)
        }

    @Test
    fun `owner without configured window gets null window and zone`() =
        runTest {
            coEvery { appSettings.getBoolean(any(), any()) } returns true
            coEvery { scheduleService.isEnabled() } returns false
            coEvery { scheduleService.getWindow() } returns null
            coEvery { scheduleService.getZone() } returns null

            val state = factory.build(user(languageCode = null), isOwner = true)

            assertEquals(false, state.scheduleEnabled)
            assertNull(state.scheduleWindow)
            assertNull(state.scheduleZone)
            assertEquals("en", state.language)
        }
}
