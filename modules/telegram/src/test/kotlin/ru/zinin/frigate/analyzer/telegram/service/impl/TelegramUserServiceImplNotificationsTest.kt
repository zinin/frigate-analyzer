package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.entity.TelegramUserEntity
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.repository.TelegramUserRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramUserServiceImplNotificationsTest {
    private val repository = mockk<TelegramUserRepository>()
    private val uuidGeneratorHelper = mockk<UUIDGeneratorHelper>()
    private val clock = Clock.systemUTC()
    private val telegramProperties = mockk<TelegramProperties>().also {
        every { it.owner } returns "owner_username"
    }
    private val service = TelegramUserServiceImpl(repository, uuidGeneratorHelper, clock, telegramProperties)

    private fun createUserEntity(
        chatId: Long? = 123L,
        recordingEnabled: Boolean = true,
        signalEnabled: Boolean = true,
    ) = TelegramUserEntity(
        id = UUID.randomUUID(),
        username = "testuser",
        chatId = chatId,
        userId = 100L,
        firstName = "Test",
        lastName = "User",
        status = UserStatus.ACTIVE.name,
        creationTimestamp = Instant.now(),
        activationTimestamp = Instant.now(),
        notificationsRecordingEnabled = recordingEnabled,
        notificationsSignalEnabled = signalEnabled,
    )

    @Test
    fun `updateNotificationsRecordingEnabled returns true when row updated`() = runTest {
        coEvery { repository.updateNotificationsRecordingEnabled(123L, false) } returns 1L

        val result = service.updateNotificationsRecordingEnabled(123L, false)

        assertTrue(result)
    }

    @Test
    fun `updateNotificationsRecordingEnabled returns false when no rows updated`() = runTest {
        coEvery { repository.updateNotificationsRecordingEnabled(999L, false) } returns 0L

        val result = service.updateNotificationsRecordingEnabled(999L, false)

        assertFalse(result)
    }

    @Test
    fun `updateNotificationsSignalEnabled returns true when row updated`() = runTest {
        coEvery { repository.updateNotificationsSignalEnabled(123L, false) } returns 1L

        val result = service.updateNotificationsSignalEnabled(123L, false)

        assertTrue(result)
    }

    @Test
    fun `updateNotificationsSignalEnabled returns false when no rows updated`() = runTest {
        coEvery { repository.updateNotificationsSignalEnabled(999L, false) } returns 0L

        val result = service.updateNotificationsSignalEnabled(999L, false)

        assertFalse(result)
    }

    @Test
    fun `getAuthorizedUsersWithZones populates notification flags`() = runTest {
        val users = listOf(
            createUserEntity(chatId = 1L, recordingEnabled = true, signalEnabled = false),
            createUserEntity(chatId = 2L, recordingEnabled = false, signalEnabled = true),
        )
        coEvery { repository.findAllByStatus(UserStatus.ACTIVE.name) } returns users

        val result = service.getAuthorizedUsersWithZones()

        assertEquals(2, result.size)
        assertTrue(result[0].notificationsRecordingEnabled)
        assertFalse(result[0].notificationsSignalEnabled)
        assertFalse(result[1].notificationsRecordingEnabled)
        assertTrue(result[1].notificationsSignalEnabled)
    }
}
