package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.dto.UserZoneInfo
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.queue.SimpleTextNotificationTask
import ru.zinin.frigate.analyzer.telegram.queue.TelegramNotificationQueue
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TelegramNotificationServiceImplSignalLossTest {
    private val userService = mockk<TelegramUserService>()
    private val queue = mockk<TelegramNotificationQueue>(relaxed = true)
    private val uuid =
        mockk<UUIDGeneratorHelper>().apply {
            every { generateV1() } returns UUID.randomUUID()
        }
    private val msg = mockk<MessageResolver>(relaxed = true)
    private val formatter =
        mockk<SignalLossMessageFormatter>().apply {
            every { buildLossMessage(any(), any(), any(), any(), any()) } returns "loss-msg"
            every { buildRecoveryMessage(any(), any(), any()) } returns "recovery-msg"
        }
    private val rateLimiterProvider = mockk<ObjectProvider<DescriptionRateLimiter>>(relaxed = true)
    private val appSettings = mockk<AppSettingsService>()
    private val service =
        TelegramNotificationServiceImpl(
            userService = userService,
            notificationQueue = queue,
            uuidGeneratorHelper = uuid,
            msg = msg,
            signalLossFormatter = formatter,
            rateLimiterProvider = rateLimiterProvider,
            appSettings = appSettings,
        )

    init {
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } returns true
    }

    @Test
    fun `sendCameraSignalLost enqueues one SimpleTextNotificationTask per active user`() =
        runBlocking {
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(
                    UserZoneInfo(chatId = 100L, zone = ZoneId.of("UTC"), language = "en"),
                    UserZoneInfo(chatId = 200L, zone = ZoneId.of("Europe/Moscow"), language = "ru"),
                )
            val captured = mutableListOf<SimpleTextNotificationTask>()
            coEvery { queue.enqueue(any()) } answers {
                captured.add(arg<Any>(0) as SimpleTextNotificationTask)
            }

            service.sendCameraSignalLost(
                camId = "front_door",
                lastSeenAt = Instant.parse("2026-04-25T14:32:18Z"),
                now = Instant.parse("2026-04-25T14:35:32Z"),
            )

            assertEquals(2, captured.size)
            assertTrue(captured.map { it.chatId }.containsAll(listOf(100L, 200L)))
            assertTrue(captured.all { it.text == "loss-msg" })

            verify(exactly = 1) {
                formatter.buildLossMessage(
                    camId = "front_door",
                    lastSeenAt = any(),
                    now = any(),
                    zone = ZoneId.of("UTC"),
                    language = "en",
                )
            }
            verify(exactly = 1) {
                formatter.buildLossMessage(
                    camId = "front_door",
                    lastSeenAt = any(),
                    now = any(),
                    zone = ZoneId.of("Europe/Moscow"),
                    language = "ru",
                )
            }
        }

    @Test
    fun `sendCameraSignalLost is no-op when no active users`() =
        runBlocking {
            coEvery { userService.getAuthorizedUsersWithZones() } returns emptyList()

            service.sendCameraSignalLost(
                camId = "front_door",
                lastSeenAt = Instant.now(),
                now = Instant.now(),
            )

            coVerify(exactly = 0) { queue.enqueue(any()) }
        }

    @Test
    fun `sendCameraSignalRecovered enqueues one SimpleTextNotificationTask per active user`() =
        runBlocking {
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(
                    UserZoneInfo(chatId = 100L, zone = ZoneId.of("UTC"), language = "en"),
                )
            val captured = slot<SimpleTextNotificationTask>()
            coEvery { queue.enqueue(capture(captured)) } answers { /* no-op */ }

            service.sendCameraSignalRecovered(
                camId = "front_door",
                downtime = Duration.ofMinutes(12).plusSeconds(48),
            )

            assertEquals(100L, captured.captured.chatId)
            assertEquals("recovery-msg", captured.captured.text)

            verify(exactly = 1) {
                formatter.buildRecoveryMessage(
                    camId = "front_door",
                    downtime = Duration.ofMinutes(12).plusSeconds(48),
                    language = "en",
                )
            }
        }

    @Test
    fun `sendCameraSignalRecovered is no-op when no active users`() =
        runBlocking {
            coEvery { userService.getAuthorizedUsersWithZones() } returns emptyList()

            service.sendCameraSignalRecovered(camId = "front_door", downtime = Duration.ofMinutes(5))

            coVerify(exactly = 0) { queue.enqueue(any()) }
        }

    @Test
    fun `sendCameraSignalLost falls back to en when user language is null`() =
        runBlocking {
            // Locks down the `userZone.language ?: "en"` fallback in TelegramNotificationServiceImpl.
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(
                    UserZoneInfo(chatId = 100L, zone = ZoneId.of("UTC"), language = null),
                )
            coEvery { queue.enqueue(any()) } answers { /* no-op */ }

            service.sendCameraSignalLost(
                camId = "front_door",
                lastSeenAt = Instant.parse("2026-04-25T14:32:18Z"),
                now = Instant.parse("2026-04-25T14:35:32Z"),
            )

            verify(exactly = 1) {
                formatter.buildLossMessage(
                    camId = "front_door",
                    lastSeenAt = any(),
                    now = any(),
                    zone = ZoneId.of("UTC"),
                    language = "en",
                )
            }
        }

    @Test
    fun `global signal toggle off skips signal-loss alert`() = runBlocking {
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } returns false
        coEvery { userService.getAuthorizedUsersWithZones() } returns
            listOf(UserZoneInfo(chatId = 100L, zone = ZoneId.of("UTC"), language = "en"))

        service.sendCameraSignalLost(
            camId = "front_door",
            lastSeenAt = Instant.now(),
            now = Instant.now(),
        )

        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `global signal toggle off skips signal-recovery alert`() = runBlocking {
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } returns false
        coEvery { userService.getAuthorizedUsersWithZones() } returns
            listOf(UserZoneInfo(chatId = 100L, zone = ZoneId.of("UTC"), language = "en"))

        service.sendCameraSignalRecovered(camId = "front_door", downtime = Duration.ofMinutes(5))

        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `signal-loss filters out users with notificationsSignalEnabled false`() = runBlocking {
        coEvery { userService.getAuthorizedUsersWithZones() } returns
            listOf(
                UserZoneInfo(100L, ZoneId.of("UTC"), "en", notificationsSignalEnabled = false),
                UserZoneInfo(200L, ZoneId.of("UTC"), "en", notificationsSignalEnabled = true),
            )
        val captured = mutableListOf<SimpleTextNotificationTask>()
        coEvery { queue.enqueue(any()) } answers {
            captured.add(arg<Any>(0) as SimpleTextNotificationTask)
        }

        service.sendCameraSignalLost(
            camId = "front_door",
            lastSeenAt = Instant.now(),
            now = Instant.now(),
        )

        assertEquals(1, captured.size)
        assertEquals(200L, captured.first().chatId)
    }

    @Test
    fun `signal-recovery filters out users with notificationsSignalEnabled false`() = runBlocking {
        coEvery { userService.getAuthorizedUsersWithZones() } returns
            listOf(
                UserZoneInfo(100L, ZoneId.of("UTC"), "en", notificationsSignalEnabled = false),
                UserZoneInfo(200L, ZoneId.of("UTC"), "en", notificationsSignalEnabled = true),
            )
        val captured = mutableListOf<SimpleTextNotificationTask>()
        coEvery { queue.enqueue(any()) } answers {
            captured.add(arg<Any>(0) as SimpleTextNotificationTask)
        }

        service.sendCameraSignalRecovered(camId = "front_door", downtime = Duration.ofMinutes(5))

        assertEquals(1, captured.size)
        assertEquals(200L, captured.first().chatId)
    }

    @Test
    fun `signal-loss propagates AppSettings read failure (no silent fallback)`() = runBlocking {
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } throws
            RuntimeException("db down")

        assertFailsWith<RuntimeException> {
            service.sendCameraSignalLost(
                camId = "front_door",
                lastSeenAt = Instant.now(),
                now = Instant.now(),
            )
        }
    }
}
