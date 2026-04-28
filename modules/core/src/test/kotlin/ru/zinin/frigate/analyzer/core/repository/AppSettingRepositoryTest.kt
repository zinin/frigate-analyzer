package ru.zinin.frigate.analyzer.core.repository

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.junit.jupiter.SpringExtension
import ru.zinin.frigate.analyzer.core.IntegrationTestBase
import ru.zinin.frigate.analyzer.service.repository.AppSettingRepository
import java.time.Instant
import java.util.UUID

@ExtendWith(SpringExtension::class)
class AppSettingRepositoryTest : IntegrationTestBase() {
    @Autowired
    private lateinit var repository: AppSettingRepository

    @Test
    fun `upsert inserts missing setting`() {
        runBlocking {
            val key = "test.${UUID.randomUUID()}"
            val updatedAt = Instant.parse("2026-04-27T12:00:00Z")

            val updated = repository.upsert(key, "true", updatedAt, "alice")

            val actual = repository.findBySettingKey(key)
            assertEquals(1L, updated)
            assertNotNull(actual)
            assertEquals("true", actual!!.settingValue)
            assertEquals(updatedAt, actual.updatedAt)
            assertEquals("alice", actual.updatedBy)
        }
    }

    @Test
    fun `upsert updates existing setting by key`() {
        runBlocking {
            val key = "test.${UUID.randomUUID()}"
            repository.upsert(key, "true", Instant.parse("2026-04-27T12:00:00Z"), "alice")
            val updatedAt = Instant.parse("2026-04-27T13:00:00Z")

            val updated = repository.upsert(key, "false", updatedAt, "bob")

            val actual = repository.findBySettingKey(key)
            assertEquals(1L, updated)
            assertNotNull(actual)
            assertEquals("false", actual!!.settingValue)
            assertEquals(updatedAt, actual.updatedAt)
            assertEquals("bob", actual.updatedBy)
        }
    }
}
