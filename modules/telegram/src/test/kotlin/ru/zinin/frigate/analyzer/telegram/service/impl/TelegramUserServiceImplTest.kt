package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.repository.TelegramUserRepository
import java.time.Clock
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramUserServiceImplTest {
    private val repository = mockk<TelegramUserRepository>()
    private val uuidGeneratorHelper = mockk<UUIDGeneratorHelper>()
    private val clock = Clock.systemUTC()
    private val telegramProperties = mockk<TelegramProperties>().also {
        every { it.owner } returns "owner_username"
    }
    private val service = TelegramUserServiceImpl(repository, uuidGeneratorHelper, clock, telegramProperties)

    @Test
    fun `isOwner returns true for exact match`() = runTest {
        assertTrue(service.isOwner("owner_username"))
    }

    @Test
    fun `isOwner is case-insensitive`() = runTest {
        assertTrue(service.isOwner("OWNER_USERNAME"))
    }

    @Test
    fun `isOwner returns false for different user`() = runTest {
        assertFalse(service.isOwner("other_user"))
    }

    @Test
    fun `isOwner returns false for null`() = runTest {
        assertFalse(service.isOwner(null))
    }

    @Test
    fun `isOwner returns false for blank`() = runTest {
        assertFalse(service.isOwner(""))
    }

    @Test
    fun `isOwner returns false when owner config is blank`() = runTest {
        val blankProps = mockk<TelegramProperties>().also {
            every { it.owner } returns ""
        }
        val svc = TelegramUserServiceImpl(repository, uuidGeneratorHelper, clock, blankProps)
        assertFalse(svc.isOwner("owner_username"))
    }
}
