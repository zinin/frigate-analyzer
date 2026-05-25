package ru.zinin.frigate.analyzer.core.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Verifies the legacy `com.fasterxml.jackson.databind.ObjectMapper` bean only.
 *
 * Does NOT verify the REST wire format — Spring Boot 4 WebFlux uses `tools.jackson`
 * (Jackson 3) at runtime, independently of this bean. See `StatusControllerTest` for the
 * real wire-format coverage and [JacksonConfiguration] KDoc for the dual-stack context.
 */
class JacksonConfigurationTest {
    private val mapper = JacksonConfiguration().objectMapper()

    @Test
    fun `Instant serialised as ISO-8601 string`() {
        val json = mapper.writeValueAsString(Instant.parse("2026-04-25T10:00:00Z"))
        assertThat(json).isEqualTo("\"2026-04-25T10:00:00Z\"")
    }

    @Test
    fun `Duration serialised as ISO-8601 string`() {
        val json = mapper.writeValueAsString(Duration.ofMinutes(7))
        assertThat(json).isEqualTo("\"PT7M\"")
    }
}
