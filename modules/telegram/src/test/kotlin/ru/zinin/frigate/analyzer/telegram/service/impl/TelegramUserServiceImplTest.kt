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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramUserServiceImplTest {
    private val repository = mockk<TelegramUserRepository>()
    private val uuidGeneratorHelper = mockk<UUIDGeneratorHelper>()
    private val clock = Clock.systemUTC()
    private val telegramProperties =
        mockk<TelegramProperties>().also {
            every { it.owner } returns "owner_username"
        }
    private val service = TelegramUserServiceImpl(repository, uuidGeneratorHelper, clock, telegramProperties)

    private fun ownerEntity(languageCode: String?): TelegramUserEntity =
        TelegramUserEntity(
            id = UUID.randomUUID(),
            username = "owner_username",
            chatId = 42L,
            userId = 100L,
            firstName = "Owner",
            lastName = null,
            status = UserStatus.ACTIVE.name,
            creationTimestamp = Instant.now(),
            activationTimestamp = Instant.now(),
            languageCode = languageCode,
        )

    @Test
    fun `isOwner returns true for exact match`() =
        runTest {
            assertTrue(service.isOwner("owner_username"))
        }

    @Test
    fun `isOwner is case-insensitive`() =
        runTest {
            assertTrue(service.isOwner("OWNER_USERNAME"))
        }

    @Test
    fun `isOwner returns false for different user`() =
        runTest {
            assertFalse(service.isOwner("other_user"))
        }

    @Test
    fun `isOwner returns false for null`() =
        runTest {
            assertFalse(service.isOwner(null))
        }

    @Test
    fun `isOwner returns false for blank`() =
        runTest {
            assertFalse(service.isOwner(""))
        }

    @Test
    fun `isOwner returns false when owner config is blank`() =
        runTest {
            val blankProps =
                mockk<TelegramProperties>().also {
                    every { it.owner } returns ""
                }
            val svc = TelegramUserServiceImpl(repository, uuidGeneratorHelper, clock, blankProps)
            assertFalse(svc.isOwner("owner_username"))
        }

    @Test
    fun `getOwnerLanguage returns languageCode when owner exists`() =
        runTest {
            coEvery { repository.findByUsernameIgnoreCase("owner_username") } returns ownerEntity(languageCode = "ru")

            assertEquals("ru", service.getOwnerLanguage())
        }

    @Test
    fun `getOwnerLanguage returns null when owner has no languageCode`() =
        runTest {
            coEvery { repository.findByUsernameIgnoreCase("owner_username") } returns ownerEntity(languageCode = null)

            assertNull(service.getOwnerLanguage())
        }

    @Test
    fun `getOwnerLanguage returns null when owner not in database`() =
        runTest {
            coEvery { repository.findByUsernameIgnoreCase("owner_username") } returns null

            assertNull(service.getOwnerLanguage())
        }
}
