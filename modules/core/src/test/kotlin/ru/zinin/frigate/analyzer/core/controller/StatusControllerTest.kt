package ru.zinin.frigate.analyzer.core.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient
import ru.zinin.frigate.analyzer.core.IntegrationTestBase

// Note: `IntegrationTestBase` spins up Docker Compose (PostgreSQL + Liquibase) in its
// `companion object init {}`. This test inherits that dependency for parity with all
// other endpoint tests in the project. The ISO-8601 contract is verified independently
// by the DB-free `JacksonConfigurationTest` (Step 1a), so the structural assertions
// below need only confirm the endpoint exists and produces the expected JSON shape.
@AutoConfigureWebTestClient
class StatusControllerTest : IntegrationTestBase() {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `GET status returns 200 with expected structure and ISO-8601 datetimes`() {
        // In Spring Boot 4.0.3, the auto-configured WebTestClient honours
        // `spring.webflux.base-path: /frigate-analyzer` from application.yaml
        // (ReactiveWebServerApplicationContextLocalTestWebServerProvider applies it
        // to the WebTestClient's base URI). The relative URI below resolves against
        // that base, so the actual request hits `/frigate-analyzer/status` — the real
        // external contract — without us having to repeat the prefix here.
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
}
