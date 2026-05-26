package ru.zinin.frigate.analyzer.ai.description.testsupport

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TestObjectMappersTest {
    @Test
    fun `internalMapper deserialises Kotlin data class via KotlinModule`() {
        data class Foo(
            val name: String,
        )
        val parsed =
            TestObjectMappers
                .internalMapper()
                .readValue("""{"name":"x"}""", Foo::class.java)
        assertThat(parsed.name).isEqualTo("x")
    }

    @Test
    fun `internalMapper tolerates unknown properties`() {
        data class Foo(
            val known: String,
        )
        val parsed =
            TestObjectMappers
                .internalMapper()
                .readValue("""{"known":"x","unknown":"ignored"}""", Foo::class.java)
        assertThat(parsed.known).isEqualTo("x")
    }

    @Test
    fun `internalMapper serialises Instant as ISO-8601 string`() {
        // Parity with core's TestObjectMappersTest — guards DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS=false
        // on the ai-description factory copy. A regression here would surface as numeric timestamps
        // in Claude prompts, which the SDK could mis-interpret.
        val json = TestObjectMappers.internalMapper().writeValueAsString(Instant.parse("2026-05-26T10:00:00Z"))
        assertThat(json).isEqualTo("\"2026-05-26T10:00:00Z\"")
    }

    @Test
    fun `internalMapper serialises Duration as ISO-8601 string`() {
        val json = TestObjectMappers.internalMapper().writeValueAsString(Duration.ofMinutes(7))
        assertThat(json).isEqualTo("\"PT7M\"")
    }
}
