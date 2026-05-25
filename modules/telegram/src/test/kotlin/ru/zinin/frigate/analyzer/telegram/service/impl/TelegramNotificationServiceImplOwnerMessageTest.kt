package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.queue.SimpleTextNotificationTask
import ru.zinin.frigate.analyzer.telegram.queue.TelegramNotificationQueue
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Instant
import java.util.UUID

class TelegramNotificationServiceImplOwnerMessageTest {
    private val ownerUsername = "alice"

    private val userService = mockk<TelegramUserService>()
    private val notificationQueue = mockk<TelegramNotificationQueue>()
    private val uuidGeneratorHelper = mockk<UUIDGeneratorHelper>()
    private val msg = mockk<MessageResolver>(relaxed = true)
    private val signalLossFormatter = mockk<SignalLossMessageFormatter>(relaxed = true)
    private val rateLimiterProvider = mockk<ObjectProvider<DescriptionRateLimiter>>(relaxed = true)
    private val appSettings = mockk<AppSettingsService>(relaxed = true)
    private val telegramProperties =
        TelegramProperties(enabled = true, botToken = "x", owner = ownerUsername)

    private val service =
        TelegramNotificationServiceImpl(
            userService = userService,
            notificationQueue = notificationQueue,
            uuidGeneratorHelper = uuidGeneratorHelper,
            msg = msg,
            signalLossFormatter = signalLossFormatter,
            rateLimiterProvider = rateLimiterProvider,
            appSettings = appSettings,
            telegramProperties = telegramProperties,
        )

    private fun ownerDto(chatId: Long?): TelegramUserDto =
        TelegramUserDto(
            id = UUID.randomUUID(),
            username = ownerUsername,
            chatId = chatId,
            userId = 1L,
            firstName = null,
            lastName = null,
            status = UserStatus.ACTIVE,
            creationTimestamp = Instant.now(),
            activationTimestamp = Instant.now(),
        )

    @Test
    fun `sendOwnerMessage enqueues SimpleTextNotificationTask to owner chat`() =
        runTest {
            val ownerId = UUID.randomUUID()
            every { uuidGeneratorHelper.generateV1() } returns ownerId
            coEvery { userService.findByUsernameIgnoreCase(ownerUsername) } returns ownerDto(chatId = 42L)
            coEvery { notificationQueue.enqueue(any()) } just Runs

            service.sendOwnerMessage("Hello, admin!")

            coVerify(exactly = 1) {
                notificationQueue.enqueue(
                    match<SimpleTextNotificationTask> {
                        it.id == ownerId && it.chatId == 42L && it.text == "Hello, admin!"
                    },
                )
            }
        }

    @Test
    fun `sendOwnerMessage does nothing when owner is not active yet`() =
        runTest {
            coEvery { userService.findByUsernameIgnoreCase(ownerUsername) } returns null

            service.sendOwnerMessage("Hello, admin!")

            coVerify(exactly = 0) { notificationQueue.enqueue(any()) }
        }

    @Test
    fun `sendOwnerMessage does nothing when owner has no chatId`() =
        runTest {
            coEvery { userService.findByUsernameIgnoreCase(ownerUsername) } returns ownerDto(chatId = null)

            service.sendOwnerMessage("Hello, admin!")

            coVerify(exactly = 0) { notificationQueue.enqueue(any()) }
        }
}
