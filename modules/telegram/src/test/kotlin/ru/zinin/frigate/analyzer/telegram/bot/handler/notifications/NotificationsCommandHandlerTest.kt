package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.Username
import dev.inmo.tgbotapi.types.chat.PrivateChatImpl
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.dto.NotificationsViewState
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationsCommandHandlerTest {
    private val userService = mockk<TelegramUserService>()
    private val appSettings = mockk<AppSettingsService>()
    private val renderer = mockk<NotificationsMessageRenderer>(relaxed = true)
    private val handler = NotificationsCommandHandler(userService, appSettings, renderer)

    private val chatId = ChatId(RawChatId(123L))

    private fun createUserDto(
        username: String = "testuser",
        languageCode: String? = "en",
        recordingEnabled: Boolean = true,
        signalEnabled: Boolean = true,
    ) = TelegramUserDto(
        id = java.util.UUID.randomUUID(),
        username = username,
        chatId = 123L,
        userId = 100L,
        firstName = "Test",
        lastName = "User",
        status = ru.zinin.frigate.analyzer.telegram.model.UserStatus.ACTIVE,
        creationTimestamp = java.time.Instant.now(),
        activationTimestamp = java.time.Instant.now(),
        languageCode = languageCode,
        notificationsRecordingEnabled = recordingEnabled,
        notificationsSignalEnabled = signalEnabled,
    )

    private fun mockMessage() =
        mockk<PrivateContentMessage<TextContent>>(relaxed = true).also {
            every { it.chat } returns PrivateChatImpl(id = chatId, username = Username("@testuser"))
        }

    @Test
    fun `handler has correct command metadata`() {
        assertEquals("notifications", handler.command)
        assertEquals(UserRole.USER, handler.requiredRole)
        assertFalse(handler.ownerOnly)
        assertEquals(7, handler.order)
    }

    @Test
    fun `USER non-owner gets recordingGlobalEnabled=null signalGlobalEnabled=null and appSettings NOT called`() =
        runTest {
            val user = createUserDto(username = "regular_user")
            val message = mockMessage()
            val stateSlot = slot<NotificationsViewState>()
            every { userService.isOwner("regular_user") } returns false
            coEvery { renderer.render(capture(stateSlot)) } returns
                RenderedNotifications("ignored", mockk(relaxed = true))

            val ctx = mockk<BehaviourContext>(relaxed = true)
            with(ctx) { with(handler) { handle(message, user) } }

            val captured = stateSlot.captured
            assertFalse(captured.isOwner)
            assertNull(captured.recordingGlobalEnabled)
            assertNull(captured.signalGlobalEnabled)
            coVerify(exactly = 0) { appSettings.getBoolean(any(), any()) }
        }

    @Test
    fun `OWNER gets non-null globals from appSettings and each getBoolean called once`() =
        runTest {
            val user = createUserDto(username = "owner_user")
            val message = mockMessage()
            val stateSlot = slot<NotificationsViewState>()
            every { userService.isOwner("owner_user") } returns true
            coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } returns false
            coEvery { renderer.render(capture(stateSlot)) } returns
                RenderedNotifications("ignored", mockk(relaxed = true))

            val ctx = mockk<BehaviourContext>(relaxed = true)
            with(ctx) { with(handler) { handle(message, user) } }

            val captured = stateSlot.captured
            assertTrue(captured.isOwner)
            assertEquals(true, captured.recordingGlobalEnabled)
            assertEquals(false, captured.signalGlobalEnabled)
            coVerify(exactly = 1) { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) }
            coVerify(exactly = 1) { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) }
        }

    @Test
    fun `appSettings getBoolean throws for OWNER exception propagates`() =
        runTest {
            val user = createUserDto(username = "owner_user")
            val message = mockMessage()
            every { userService.isOwner("owner_user") } returns true
            coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } throws
                RuntimeException("db down")

            val ctx = mockk<BehaviourContext>(relaxed = true)
            try {
                with(ctx) { with(handler) { handle(message, user) } }
                kotlin.test.fail("expected exception")
            } catch (e: RuntimeException) {
                assertEquals("db down", e.message)
            }
        }

    @Test
    fun `isOwner uses user dot username from TelegramUserDto`() =
        runTest {
            val user = createUserDto(username = "alice_custom")
            val message = mockMessage()
            val stateSlot = slot<NotificationsViewState>()
            every { userService.isOwner("alice_custom") } returns true
            coEvery { appSettings.getBoolean(any(), any()) } returns true
            coEvery { renderer.render(capture(stateSlot)) } returns
                RenderedNotifications("ignored", mockk(relaxed = true))

            val ctx = mockk<BehaviourContext>(relaxed = true)
            with(ctx) { with(handler) { handle(message, user) } }

            assertTrue(stateSlot.captured.isOwner)
        }

    @Test
    fun `user language defaults to en when languageCode is null`() =
        runTest {
            val user = createUserDto(username = "regular_user", languageCode = null)
            val message = mockMessage()
            val stateSlot = slot<NotificationsViewState>()
            every { userService.isOwner("regular_user") } returns false
            coEvery { renderer.render(capture(stateSlot)) } returns
                RenderedNotifications("ignored", mockk(relaxed = true))

            val ctx = mockk<BehaviourContext>(relaxed = true)
            with(ctx) { with(handler) { handle(message, user) } }

            assertEquals("en", stateSlot.captured.language)
        }

    @Test
    fun `user null does nothing and does not call renderer or appSettings`() =
        runTest {
            val message = mockMessage()

            val ctx = mockk<BehaviourContext>(relaxed = true)
            with(ctx) { with(handler) { handle(message, null) } }

            coVerify(exactly = 0) { renderer.render(any()) }
            coVerify(exactly = 0) { appSettings.getBoolean(any(), any()) }
        }
}
