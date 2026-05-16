package ru.zinin.frigate.analyzer.core

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.buildBot
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

/**
 * Smoke-test that exercises the real Telegram bot startup path
 * (Spring -> TelegramBot bean -> ktor HttpClient -> bot.getMe()) end-to-end
 * against a MockWebServer. Existing unit tests mock the bot, so the real ktor
 * HttpClient is never instantiated and binary-incompat between ktor and
 * kotlinx-coroutines / kotlinx-serialization (e.g. NoSuchMethodError from a
 * version-pin mismatch) slips past CI. This test fills that gap.
 */
@Import(TelegramBotStartupIntegrationTest.MockTelegramConfig::class)
class TelegramBotStartupIntegrationTest : IntegrationTestBase() {
    @TestConfiguration
    class MockTelegramConfig {
        @Bean
        @Primary
        fun mockTelegramBot(
            properties: TelegramProperties,
            @Value("\${mock-telegram.url}") apiUrl: String,
        ): TelegramBot = buildBot(token = properties.botToken, apiUrl = apiUrl) {}
    }

    companion object {
        private lateinit var mockTelegram: MockWebServer

        // Minimal valid Telegram getMe response. Reused for all enqueued slots;
        // long-polling getUpdates calls will also consume entries here but the
        // assertion only inspects the first request.
        private val getMeBody =
            """{"ok":true,"result":{"id":42,"is_bot":true,"first_name":"Test",""" +
                """"username":"testbot","can_join_groups":true,""" +
                """"can_read_all_group_messages":false,"supports_inline_queries":false}}"""

        @JvmStatic
        @BeforeAll
        fun startMockTelegram() {
            mockTelegram = MockWebServer()
            repeat(20) {
                mockTelegram.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(getMeBody)
                        .build(),
                )
            }
            mockTelegram.start()
        }

        @JvmStatic
        @AfterAll
        fun stopMockTelegram() {
            mockTelegram.close()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("application.telegram.enabled") { "true" }
            registry.add("application.telegram.bot-token") { "test-token" }
            registry.add("application.telegram.owner") { "test-owner" }
            registry.add("mock-telegram.url") { mockTelegram.url("/").toString().trimEnd('/') }
        }
    }

    @Test
    fun `Telegram bot startup invokes ktor HTTP client without binary incompat`() {
        // FrigateAnalyzerBot.@PostConstruct launches a coroutine that calls bot.getMe().
        // If ktor / kotlinx-coroutines / kotlinx-serialization are binary-incompat at
        // runtime, the ktor pipeline throws NoSuchMethodError and the request never
        // reaches the mock. The bot swallows exceptions into logs, so we assert on the
        // side-effect (HTTP traffic) rather than catching a throw.
        val recorded = mockTelegram.takeRequest(10, TimeUnit.SECONDS)
        assertNotNull(
            recorded,
            "Telegram bot did not make any HTTP request within 10s. " +
                "Most likely cause: ktor/coroutines binary-incompat (NoSuchMethodError) at client init.",
        )
    }
}
