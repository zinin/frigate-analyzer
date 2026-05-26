package ru.zinin.frigate.analyzer.ai.description.testsupport

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Test-side factory matching the production `@Primary internalObjectMapper` bean from the
 * core module's `JacksonConfiguration`. Duplicated here because Gradle modules don't share
 * test sources; keep the body in sync with the core copy.
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
