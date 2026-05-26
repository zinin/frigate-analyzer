package ru.zinin.frigate.analyzer.ai.description.testsupport

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
}
