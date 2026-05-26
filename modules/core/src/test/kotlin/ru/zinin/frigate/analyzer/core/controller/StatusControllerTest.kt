package ru.zinin.frigate.analyzer.core.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import ru.zinin.frigate.analyzer.core.IntegrationTestBase

// Note: `IntegrationTestBase` spins up Docker Compose (PostgreSQL + Liquibase) in its
// `companion object init {}`. This test inherits that dependency for parity with all
// other endpoint tests in the project.
//
// `StatusControllerTestConfig` injects a mocked `SignalLossMonitorTask` with one
// fixed OFFLINE camera so the response actually contains `Instant`/`Duration` values —
// without it, the test context has `signal-loss.enabled` unset (matchIfMissing=false),
// so the real monitor bean is absent and `cameras.items` is empty, making ISO-8601
// wire-format assertions impossible.
//
// The wire-format test below is an end-to-end sanity check для `/status`:
// ISO-8601 timestamps + ожидаемая структура JSON-ответа. После Jackson 3 migration
// (issue #29) запрос идёт через codecs, зарегистрированные `WebFluxJacksonCodecConfigurer`
// от нашего `@Primary internalObjectMapper`.
//
// Важно: этот тест НЕ доказывает что наш `WebFluxJacksonCodecConfigurer` фактически
// управляет codec'ом. Spring Boot 4 `CodecsAutoConfiguration.jacksonCodecCustomizer`
// автоматически wire'ит `@Primary JsonMapper` бин в WebFlux codec — все наши настройки
// совпадают с Boot 4 defaults, поэтому удаление нашего configurer'а не сломает ни этот
// тест, ни поведение `/status`. Это honest sanity check, не regression guard.
//
// Реальный regression guard configurer'а:
//  - `WebFluxJacksonCodecConfigurerTest` — unit test с identity check через публичный
//    `JacksonCodecSupport.getMapper()`: codec'ы построены ИМЕННО на нашем mapper'е.
//  - `JacksonConfigurationTest` — `ApplicationContextRunner` с auto-config: `@Primary`
//    disambiguation в bean topology.
//
// См. KDoc на [JacksonConfiguration] (раздел про «Builder vs pre-built — осознанный trade-off»)
// и [WebFluxJacksonCodecConfigurer] (раздел «Honest narrative про duplication») для полного
// объяснения.
@AutoConfigureWebTestClient
@Import(StatusControllerTestConfig::class)
class StatusControllerTest : IntegrationTestBase() {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `GET status returns 200 with expected structure`() {
        // In Spring Boot 4.0.3, the auto-configured WebTestClient honours
        // `spring.webflux.base-path: /frigate-analyzer` from application.yaml.
        // The relative URI below resolves against that base, so the actual request
        // hits `/frigate-analyzer/status` — the real external contract.
        webTestClient
            .get()
            .uri("/status")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.recordings.total")
            .isNumber
            .jsonPath("$.recordings.processed")
            .isNumber
            .jsonPath("$.recordings.unprocessed")
            .isNumber
            .jsonPath("$.recordings.success")
            .isNumber
            .jsonPath("$.recordings.errors")
            .isNumber
            .jsonPath("$.recordings.byCameras")
            .isArray
            .jsonPath("$.recordings.processingRatePerMinute")
            .isNumber
            .jsonPath("$.cameras.monitoringEnabled")
            .isBoolean
            .jsonPath("$.cameras.items")
            .isArray
            .jsonPath("$.detectServers")
            .isArray
    }

    @Test
    fun `GET status serialises Instant as ISO-8601 string and Duration as ISO-8601 duration string`() {
        // The mocked SignalLossMonitorTask (StatusControllerTestConfig) returns one
        // OFFLINE camera with lastSeenAt = 2026-04-25T10:00:00Z. The exact `offlineFor`
        // value is non-deterministic (depends on Clock at request time), so we assert
        // only the ISO-8601 prefix (`PT...`) for Duration. For Instant we pin the exact
        // value because `lastSeenAt` is passed through unmodified.
        webTestClient
            .get()
            .uri("/status")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.cameras.monitoringEnabled")
            .isEqualTo(true)
            .jsonPath("$.cameras.items[0].camId")
            .isEqualTo("cam-wire-test")
            .jsonPath("$.cameras.items[0].state")
            .isEqualTo("OFFLINE")
            .jsonPath("$.cameras.items[0].lastSeenAt")
            .isEqualTo("2026-04-25T10:00:00Z")
            .jsonPath("$.cameras.items[0].offlineFor")
            .value<String> { value ->
                assert(value.startsWith("PT")) {
                    "Expected ISO-8601 Duration starting with 'PT', got: $value"
                }
            }
    }
}
