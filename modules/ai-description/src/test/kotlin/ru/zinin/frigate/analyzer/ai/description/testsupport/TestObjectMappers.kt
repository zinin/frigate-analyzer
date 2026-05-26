package ru.zinin.frigate.analyzer.ai.description.testsupport

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Test-side factory matching the production `@Primary internalObjectMapper` bean from the
 * core module's `JacksonConfiguration`.
 *
 * **STRICT COPY of**
 * `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/TestObjectMappers.kt#internalMapper`.
 *
 * Gradle modules don't share test sources, so this file is a literal duplicate of the core
 * test factory. **When changing this body, mirror the change to BOTH:**
 *   1. Production `ru.zinin.frigate.analyzer.core.config.JacksonConfiguration.internalObjectMapper`
 *   2. The core test copy referenced above
 *
 * No compile-time enforcement; mirroring is caught at PR-review time. Extraction to test
 * fixtures / shared testsupport module intentionally **out of scope of issue #29**.
 *
 * Return type is `JsonMapper` to match production. `JsonMapper extends ObjectMapper`, so
 * `ClaudeResponseParser`'s `ObjectMapper` parameter is satisfied transparently.
 *
 * Jackson 3 note: `WRITE_DATES_AS_TIMESTAMPS`/`WRITE_DURATIONS_AS_TIMESTAMPS` находятся в
 * `tools.jackson.databind.cfg.DateTimeFeature`, не в `SerializationFeature` (Jackson 2 расположение).
 */
object TestObjectMappers {
    fun internalMapper(): JsonMapper =
        JsonMapper
            .builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()
}
