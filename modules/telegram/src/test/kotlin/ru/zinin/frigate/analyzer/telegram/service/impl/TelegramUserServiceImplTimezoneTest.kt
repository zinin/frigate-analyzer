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
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramUserServiceImplTimezoneTest {
    private val repository = mockk<TelegramUserRepository>()
    private val uuidGeneratorHelper = mockk<UUIDGeneratorHelper>()
    private val clock = Clock.systemUTC()
    private val telegramProperties = mockk<TelegramProperties>().also {
        every { it.owner } returns "owner_username"
    }
    private val service = TelegramUserServiceImpl(repository, uuidGeneratorHelper, clock, telegramProperties)

    private fun createUserEntity(
        chatId: Long? = 123L,
        olsonCode: String? = null,
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
        olsonCode = olsonCode,
    )

    // --- getUserZone ---

    @Test
    fun `getUserZone returns UTC when user not found`() =
        runTest {
            coEvery { repository.findByChatId(999L) } returns null

            val zone = service.getUserZone(999L)

            assertEquals(ZoneId.of("UTC"), zone)
        }

    @Test
    fun `getUserZone returns UTC when olsonCode is null`() =
        runTest {
            coEvery { repository.findByChatId(123L) } returns createUserEntity(olsonCode = null)

            val zone = service.getUserZone(123L)

            assertEquals(ZoneId.of("UTC"), zone)
        }

    @Test
    fun `getUserZone returns correct zone for valid olsonCode`() =
        runTest {
            coEvery { repository.findByChatId(123L) } returns createUserEntity(olsonCode = "Europe/Moscow")

            val zone = service.getUserZone(123L)

            assertEquals(ZoneId.of("Europe/Moscow"), zone)
        }

    @Test
    fun `getUserZone falls back to UTC for invalid olsonCode`() =
        runTest {
            coEvery { repository.findByChatId(123L) } returns createUserEntity(olsonCode = "Invalid/Zone")

            val zone = service.getUserZone(123L)

            assertEquals(ZoneId.of("UTC"), zone)
        }

    // --- updateTimezone ---

    @Test
    fun `updateTimezone rejects offset-based zone without slash`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                service.updateTimezone(123L, "GMT+3")
            }
        }

    @Test
    fun `updateTimezone rejects UTC offset`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                service.updateTimezone(123L, "UTC")
            }
        }

    @Test
    fun `updateTimezone accepts valid Olson ID`() =
        runTest {
            coEvery { repository.updateOlsonCode(123L, "Europe/Moscow") } returns 1L

            val result = service.updateTimezone(123L, "Europe/Moscow")

            assertEquals(true, result)
        }

    @Test
    fun `updateTimezone returns false when no rows updated`() =
        runTest {
            coEvery { repository.updateOlsonCode(123L, "Europe/Moscow") } returns 0L

            val result = service.updateTimezone(123L, "Europe/Moscow")

            assertEquals(false, result)
        }

    @Test
    fun `updateTimezone rejects invalid Olson ID with slash`() =
        runTest {
            assertFailsWith<java.time.DateTimeException> {
                service.updateTimezone(123L, "Invalid/Zone")
            }
        }

    // --- getAuthorizedUsersWithZones ---

    @Test
    fun `getAuthorizedUsersWithZones returns correct zones`() =
        runTest {
            val users =
                listOf(
                    createUserEntity(chatId = 1L, olsonCode = "Europe/Moscow"),
                    createUserEntity(chatId = 2L, olsonCode = "Asia/Tokyo"),
                )
            coEvery { repository.findAllByStatus(UserStatus.ACTIVE.name) } returns users

            val result = service.getAuthorizedUsersWithZones()

            assertEquals(2, result.size)
            assertEquals(1L, result[0].chatId)
            assertEquals(ZoneId.of("Europe/Moscow"), result[0].zone)
            assertEquals(2L, result[1].chatId)
            assertEquals(ZoneId.of("Asia/Tokyo"), result[1].zone)
        }

    @Test
    fun `getAuthorizedUsersWithZones falls back to UTC for invalid olsonCode`() =
        runTest {
            val users =
                listOf(
                    createUserEntity(chatId = 1L, olsonCode = "Europe/Moscow"),
                    createUserEntity(chatId = 2L, olsonCode = "Invalid/Zone"),
                    createUserEntity(chatId = 3L, olsonCode = null),
                )
            coEvery { repository.findAllByStatus(UserStatus.ACTIVE.name) } returns users

            val result = service.getAuthorizedUsersWithZones()

            assertEquals(3, result.size)
            assertEquals(ZoneId.of("Europe/Moscow"), result[0].zone)
            assertEquals(ZoneId.of("UTC"), result[1].zone)
            assertEquals(ZoneId.of("UTC"), result[2].zone)
        }

    @Test
    fun `getAuthorizedUsersWithZones skips users without chatId`() =
        runTest {
            val users =
                listOf(
                    createUserEntity(chatId = 1L, olsonCode = "Europe/Moscow"),
                    createUserEntity(chatId = null, olsonCode = "Asia/Tokyo"),
                )
            coEvery { repository.findAllByStatus(UserStatus.ACTIVE.name) } returns users

            val result = service.getAuthorizedUsersWithZones()

            assertEquals(1, result.size)
            assertEquals(1L, result[0].chatId)
        }
}
