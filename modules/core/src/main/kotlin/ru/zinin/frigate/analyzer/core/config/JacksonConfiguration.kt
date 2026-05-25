package ru.zinin.frigate.analyzer.core.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * BEWARE: this mapper is from the **legacy** `com.fasterxml.jackson` package (Jackson 2). In
 * Spring Boot 4 the WebFlux JSON codecs were migrated to the new `tools.jackson` package
 * (Jackson 3) — see `WebClientConfiguration` for the project's `tools.jackson` usage. So this
 * `ObjectMapper` bean **does NOT** control the JSON wire format of `/status` (or any other
 * REST endpoint); WebFlux uses its own auto-configured `tools.jackson` mapper there.
 *
 * The bean is retained because `DetectService` and `ClaudeResponseParser` (and their tests)
 * still inject `com.fasterxml.jackson.databind.ObjectMapper` for internal parsing. Migrating
 * everything to `tools.jackson` is tracked separately — see
 * `docs/issues/2026-05-25-dual-jackson-stack.md`.
 *
 * The ISO-8601 contract for `/status` is verified end-to-end via the wire-format test in
 * `StatusControllerTest` (not via `JacksonConfigurationTest`, which only exercises THIS bean).
 */
@Configuration
class JacksonConfiguration {
    @Bean
    fun objectMapper(): ObjectMapper =
        JsonMapper
            .builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()
}
