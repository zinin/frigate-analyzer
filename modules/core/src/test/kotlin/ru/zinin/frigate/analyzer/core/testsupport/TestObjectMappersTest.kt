package ru.zinin.frigate.analyzer.core.testsupport

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TestObjectMappersTest {
    @Test
    fun `internalMapper serialises Instant as ISO-8601 string`() {
        val json = TestObjectMappers.internalMapper().writeValueAsString(Instant.parse("2026-05-26T10:00:00Z"))
        assertThat(json).isEqualTo("\"2026-05-26T10:00:00Z\"")
    }

    @Test
    fun `internalMapper serialises Duration as ISO-8601 string`() {
        val json = TestObjectMappers.internalMapper().writeValueAsString(Duration.ofMinutes(7))
        assertThat(json).isEqualTo("\"PT7M\"")
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

    @Test
    fun `detectServerMapper round-trips Kotlin data class with multiple required val params`() {
        // Realistic detect-server response shape (cf. JobCreatedResponse, JobStatusResponse).
        // Constructor-based decode of required val params depends on KotlinModule being
        // auto-discovered by findAndAddModules() — if the module is missing, this test fails
        // with "Cannot construct instance" instead of silently succeeding.
        data class JobResponse(
            val jobId: String,
            val statusCode: Int,
            val createdAt: String,
        )
        val parsed =
            TestObjectMappers
                .detectServerMapper()
                .readValue(
                    """{"job_id":"j-1","status_code":201,"created_at":"2026-05-26T10:00:00Z"}""",
                    JobResponse::class.java,
                )
        assertThat(parsed).isEqualTo(JobResponse("j-1", 201, "2026-05-26T10:00:00Z"))
    }
}
