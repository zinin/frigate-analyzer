package ru.zinin.frigate.analyzer.core.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import ru.zinin.frigate.analyzer.core.IntegrationTestBase

// End-to-end sanity check для `/status` wire-format (ISO-8601 timestamps + JSON structure).
// `IntegrationTestBase` поднимает Docker Compose (PostgreSQL + Liquibase) — parity со всеми
// остальными endpoint-тестами проекта. `StatusControllerTestConfig` injects a mocked
// `StatusService` returning a fixed snapshot so the response contains concrete `Instant` /
// `Duration` values; see that file's KDoc для объяснения почему `StatusService` (не
// `SignalLossMonitorTask`).
//
// **NOT a regression guard for `WebFluxJacksonCodecConfigurer`** — Spring Boot 4's
// `CodecsAutoConfiguration.jacksonCodecCustomizer` функционально дублирует наш configurer,
// поэтому этот test пройдёт обоими путями. Реальные regression guards:
//   - `WebFluxJacksonCodecConfigurerTest` (identity check на нашем mapper'е)
//   - `JacksonConfigurationTest` (Spring context @Primary topology)
//
// См. KDoc на [WebFluxJacksonCodecConfigurer] (раздел «Honest narrative про duplication»)
// для полной архитектурной narrative.
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
            .jsonPath("$.judge.enabled")
            .isEqualTo(false)
    }

    @Test
    fun `GET status serialises Instant as ISO-8601 string and Duration as ISO-8601 duration string`() {
        // The mocked `StatusService` (StatusControllerTestConfig) returns one OFFLINE
        // camera with lastSeenAt = 2026-04-25T10:00:00Z. The exact `offlineFor`
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
