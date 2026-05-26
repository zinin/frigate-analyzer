package ru.zinin.frigate.analyzer.core.testsupport

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Test-side factories matching production JsonMapper beans configured in
 * [ru.zinin.frigate.analyzer.core.config.JacksonConfiguration] and
 * [ru.zinin.frigate.analyzer.core.config.WebClientConfiguration].
 *
 * Use these so tests stay aligned with production wire-format and parser configuration.
 *
 * **THREE-WAY SYNC requirement:** when changing [internalMapper] body, mirror the change to
 * BOTH:
 *   1. Production [ru.zinin.frigate.analyzer.core.config.JacksonConfiguration.internalObjectMapper]
 *   2. The duplicate copy in
 *      `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/testsupport/TestObjectMappers.kt#internalMapper`
 *
 * The ai-description module duplicates [internalMapper] (strict subset — no `detectServerMapper`)
 * because Gradle modules don't share test sources. Extraction to test fixtures or a shared
 * `testsupport` module is intentionally **out of scope of issue #29**; both copies are stable
 * and any update touches the test in both modules, surfacing forgotten mirroring at PR-review
 * time.
 *
 * Return type is `JsonMapper` to match production (Spring Framework 7 codec API requires JsonMapper).
 * `JsonMapper extends ObjectMapper`, so callers that accept `ObjectMapper` still work.
 *
 * Jackson 3 note: `WRITE_DATES_AS_TIMESTAMPS` и `WRITE_DURATIONS_AS_TIMESTAMPS` находятся в
 * `tools.jackson.databind.cfg.DateTimeFeature`, не в `SerializationFeature` (как было в Jackson 2).
 */
object TestObjectMappers {
    /** Matches production `@Primary internalObjectMapper`. */
    fun internalMapper(): JsonMapper =
        JsonMapper
            .builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()

    /** Matches production `detectServerObjectMapper` (SNAKE_CASE for detect-server contract). */
    fun detectServerMapper(): JsonMapper =
        JsonMapper
            .builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .findAndAddModules()
            .build()
}
