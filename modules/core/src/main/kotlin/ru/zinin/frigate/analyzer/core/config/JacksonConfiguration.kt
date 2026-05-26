package ru.zinin.frigate.analyzer.core.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Primary tools.jackson (Jackson 3) JSON mapper used by:
 *  - WebFlux REST inbound/outbound JSON codec (wired in [WebFluxJacksonCodecConfigurer])
 *  - [ru.zinin.frigate.analyzer.core.service.DetectService] (parses detect-server error bodies as raw JsonNode)
 *  - [ru.zinin.frigate.analyzer.ai.description.claude.ClaudeResponseParser] (parses Claude responses)
 *
 * Settings:
 *  - camelCase (default property naming)
 *  - ISO-8601 strings for `Instant`/`Duration` (no numeric timestamps)
 *  - tolerant deserialization (unknown properties ignored)
 *  - `findAndAddModules()` picks up `tools.jackson.module.kotlin` from classpath via ServiceLoader
 *
 * **Return type is `JsonMapper`, not `ObjectMapper`.** Spring Framework 7's `JacksonJsonEncoder`/
 * `JacksonJsonDecoder` constructors accept `JsonMapper` (или `JsonMapper.Builder` — 5 overload'ов).
 * `JsonMapper extends ObjectMapper`, so DI into consumers declaring `ObjectMapper` (DetectService,
 * ClaudeResponseParser) still works.
 *
 * **Builder vs pre-built — осознанный trade-off:**
 * Если построить бин через `(builder: JsonMapper.Builder) -> builder.configure(...).build()`,
 * Spring Boot автоматически применит `JsonMapperBuilderCustomizer`-ы: `ProblemDetailJacksonMixin`,
 * `@JacksonMixin` бины, `spring.jackson.*` properties, `MapperBuilder.findModules()`.
 * Мы намеренно используем `JsonMapper.builder()...build()` (pre-built) для **явного контроля**
 * над wire-format: external customizers могли бы незаметно изменить поведение mapper'а,
 * противоречит цели issue #29 («config truly governs wire-format»). Проект на момент написания
 * не использует `ProblemDetail` (`grep ProblemDetail` = 0 hits в репо), поэтому потеря этого
 * mixin'а не влияет на текущее поведение.
 *
 * **Dual-stack rationale (self-contained):**
 * `tools.jackson` governs all internal and REST wire-format JSON. Legacy `com.fasterxml.jackson`
 * (Jackson 2.x, BOM pinned via `gradle/libs.versions.toml`) is retained ONLY as a transitive
 * dependency of:
 *  - `springdoc-openapi-starter` 3.0.3 (requires `com.fasterxml.jackson.module.kotlin.KotlinModule`
 *    via its own `SpringDocJacksonKotlinModuleConfiguration` for OpenAPI spec generation).
 *  - other transitive consumers (YAML loaders, etc.).
 *
 * Note: no `spring-boot-starter-jackson2` exists; the deprecated `spring-boot-jackson2`
 * module is not on this app's `runtimeClasspath`. Only `spring-boot-jackson` (using
 * tools.jackson) is present.
 * The `@Primary` annotation scopes only within `tools.jackson.databind.*` classes; springdoc
 * injects `com.fasterxml.jackson.databind.ObjectMapper` (a different class), so there is no
 * type collision. Spring will never substitute incompatible types.
 */
@Configuration
class JacksonConfiguration {
    @Bean
    @Primary
    fun internalObjectMapper(): JsonMapper =
        JsonMapper
            .builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()
}
