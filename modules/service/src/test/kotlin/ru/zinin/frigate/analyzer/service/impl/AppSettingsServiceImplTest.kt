package ru.zinin.frigate.analyzer.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.persistent.AppSettingEntity
import ru.zinin.frigate.analyzer.service.repository.AppSettingRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsServiceImplTest {
    private val repo = mockk<AppSettingRepository>()
    private val fixed = Instant.parse("2026-04-27T12:00:00Z")
    private val clock = Clock.fixed(fixed, ZoneOffset.UTC)
    private val service = AppSettingsServiceImpl(repo, clock)

    @Test
    fun `getBoolean returns default when key missing`() =
        runTest {
            coEvery { repo.findBySettingKey("k") } returns null
            assertTrue(service.getBoolean("k", default = true))
            assertFalse(service.getBoolean("k", default = false))
        }

    @Test
    fun `getBoolean parses true and false`() =
        runTest {
            coEvery { repo.findBySettingKey("k") } returns
                AppSettingEntity("k", "true", fixed, null) andThen
                AppSettingEntity("k", "false", fixed, null)
            // First read populates cache; second read here uses NEW service to bypass cache
            val s1 = AppSettingsServiceImpl(repo, clock)
            val s2 = AppSettingsServiceImpl(repo, clock)
            assertTrue(s1.getBoolean("k", default = false))
            assertFalse(s2.getBoolean("k", default = true))
        }

    @Test
    fun `cache hit avoids repository on second read`() =
        runTest {
            coEvery { repo.findBySettingKey("k") } returns AppSettingEntity("k", "true", fixed, null)

            service.getBoolean("k")
            service.getBoolean("k")

            coVerify(exactly = 1) { repo.findBySettingKey("k") }
        }

    @Test
    fun `setBoolean upserts and invalidates cache`() =
        runTest {
            coEvery { repo.findBySettingKey("k") } returns AppSettingEntity("k", "true", fixed, null)
            coEvery { repo.upsert("k", "false", fixed, "alice") } returns 1L

            // populate cache
            service.getBoolean("k")
            // change
            service.setBoolean("k", value = false, updatedBy = "alice")
            // read again — must hit DB because cache invalidated
            coEvery { repo.findBySettingKey("k") } returns AppSettingEntity("k", "false", fixed, "alice")
            val v = service.getBoolean("k", default = true)

            assertFalse(v)
            coVerify(exactly = 1) { repo.upsert("k", "false", fixed, "alice") }
            coVerify(atLeast = 2) { repo.findBySettingKey("k") }
        }

    @Test
    fun `unparseable value logs WARN and falls back to default (recoverable corruption)`() =
        runTest {
            coEvery { repo.findBySettingKey("k") } returns AppSettingEntity("k", "weird", fixed, null)
            assertTrue(service.getBoolean("k", default = true))
            assertFalse(service.getBoolean("k", default = false))
        }

    @Test
    fun `getString returns stored value and caches it`() =
        runTest {
            coEvery { repo.findBySettingKey("s") } returns AppSettingEntity("s", "hello", fixed, null)

            assertEquals("hello", service.getString("s"))
            assertEquals("hello", service.getString("s"))

            coVerify(exactly = 1) { repo.findBySettingKey("s") }
        }

    @Test
    fun `getString returns default when key missing`() =
        runTest {
            coEvery { repo.findBySettingKey("missing") } returns null

            assertNull(service.getString("missing"))
            assertEquals("fallback", service.getString("missing", default = "fallback"))
        }

    @Test
    fun `setString upserts and invalidates cache`() =
        runTest {
            coEvery { repo.findBySettingKey("s") } returns AppSettingEntity("s", "old", fixed, null)
            coEvery { repo.upsert("s", "new", fixed, "alice") } returns 1L

            // populate cache
            assertEquals("old", service.getString("s"))
            // change
            service.setString("s", "new", updatedBy = "alice")
            // read again — must hit DB because cache invalidated
            coEvery { repo.findBySettingKey("s") } returns AppSettingEntity("s", "new", fixed, "alice")
            assertEquals("new", service.getString("s"))

            coVerify(exactly = 1) { repo.upsert("s", "new", fixed, "alice") }
            coVerify(atLeast = 2) { repo.findBySettingKey("s") }
        }

    @Test
    fun `absent key is negatively cached until invalidated`() =
        runTest {
            coEvery { repo.findBySettingKey("absent") } returns null

            assertNull(service.getString("absent"))
            assertNull(service.getString("absent"))
            assertFalse(service.getBoolean("absent", default = false))

            coVerify(exactly = 1) { repo.findBySettingKey("absent") }
        }

    @Test
    fun `setString clears the negative cache entry`() =
        runTest {
            coEvery { repo.findBySettingKey("nk") } returns null
            assertNull(service.getString("nk"))

            coEvery { repo.upsert("nk", "v", fixed, null) } returns 1L
            service.setString("nk", "v")

            coEvery { repo.findBySettingKey("nk") } returns AppSettingEntity("nk", "v", fixed, null)
            assertEquals("v", service.getString("nk"))
            coVerify(exactly = 2) { repo.findBySettingKey("nk") }
        }

    @Test
    fun `failed repository read caches nothing (errors stay transient)`() =
        runTest {
            coEvery { repo.findBySettingKey("boom") } throws RuntimeException("db down")

            // The failure reaches the caller — it is never swallowed into an "absent" mark
            assertFailsWith<RuntimeException> { service.getString("boom") }

            // ...and it cached nothing, so the next read hits the DB and sees the real value
            coEvery { repo.findBySettingKey("boom") } returns AppSettingEntity("boom", "v", fixed, null)
            assertEquals("v", service.getString("boom"))

            coVerify(exactly = 2) { repo.findBySettingKey("boom") }
        }
}
