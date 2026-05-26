package ru.zinin.frigate.analyzer.core.testsupport

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class TestObjectMappersTest {
    @Test
    fun `internalMapper serialises Instant as ISO-8601 string`() {
        val json = TestObjectMappers.internalMapper().writeValueAsString(Instant.parse("2026-05-26T10:00:00Z"))
        assertThat(json).isEqualTo("\"2026-05-26T10:00:00Z\"")
    }

    @Test
    fun `internalMapper deserialises unknown properties without failing`() {
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
    fun `detectServerMapper applies SNAKE_CASE naming strategy`() {
        data class Foo(
            val someField: String,
        )
        val parsed =
            TestObjectMappers
                .detectServerMapper()
                .readValue("""{"some_field":"value"}""", Foo::class.java)
        assertThat(parsed.someField).isEqualTo("value")
    }
}
