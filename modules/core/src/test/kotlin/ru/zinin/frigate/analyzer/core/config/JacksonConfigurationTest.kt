package ru.zinin.frigate.analyzer.core.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * Verifies the `@Primary internalObjectMapper` bean configured in [JacksonConfiguration].
 *
 * End-to-end wire-format coverage for REST endpoints lives in
 * [ru.zinin.frigate.analyzer.core.controller.StatusControllerTest]; this test exercises the
 * bean in isolation (settings + KotlinModule discovery + Spring-context registration).
 */
class JacksonConfigurationTest {
    private val mapper = JacksonConfiguration().internalObjectMapper()

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

    @Test
    fun `KotlinModule is auto-discovered — data class round-trips`() {
        // Regression guard for findAndAddModules() ServiceLoader discovery — fails if
        // jackson-module-kotlin-3 artifact is missing or its META-INF/services file is not packaged.
        data class Foo(
            val name: String,
            val count: Int,
        )
        val original = Foo("x", 42)
        val json = mapper.writeValueAsString(original)
        val parsed = mapper.readValue(json, Foo::class.java)
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `FAIL_ON_UNKNOWN_PROPERTIES is disabled — unknown JSON fields tolerated`() {
        // Explicit guard on the most behaviour-changing feature configured in production.
        data class Foo(
            val known: String,
        )
        val parsed = mapper.readValue("""{"known":"x","unknown":"ignored"}""", Foo::class.java)
        assertThat(parsed.known).isEqualTo("x")
    }

    @Test
    fun `Spring context with JacksonAutoConfiguration registers internalObjectMapper as the @Primary JsonMapper`() {
        // Loads our JacksonConfiguration alongside Spring Boot 4's JacksonAutoConfiguration to verify
        // the bean topology that actually exists in production.
        //
        // Spring Boot 4 reality: `JacksonAutoConfiguration.jacksonJsonMapper(JsonMapper.Builder)` is
        // annotated `@ConditionalOnMissingBean`, so when our `@Primary internalObjectMapper(): JsonMapper`
        // is registered first, Boot's bean is intentionally NOT registered. Our bean is the sole
        // `JsonMapper` in the context — which is exactly the wire-format ownership the issue #29
        // architecture targets: config (our @Primary bean) governs JsonMapper resolution end-to-end.
        //
        // This test guards the two real regressions:
        //   1. Someone removes `@Primary` from our bean → `bd.isPrimary` check fails.
        //   2. Someone introduces a second JsonMapper bean elsewhere (e.g. another @Configuration
        //      registering a builder-based bean) → `getBean(JsonMapper::class.java)` either still
        //      resolves to our bean (because of @Primary) or throws NoUniqueBeanDefinitionException
        //      if @Primary was also lost. Either failure mode is detected.
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
            .withUserConfiguration(JacksonConfiguration::class.java)
            .run { ctx ->
                val bean = ctx.getBean(JsonMapper::class.java)
                assertThat(bean).isSameAs(ctx.getBean("internalObjectMapper", JsonMapper::class.java))
                val bd = ctx.beanFactory.getBeanDefinition("internalObjectMapper")
                assertThat(bd.isPrimary).isTrue()
                // Boot's `@ConditionalOnMissingBean` in JacksonAutoConfiguration must suppress its own
                // `jacksonJsonMapper` bean — otherwise our `@Primary` would only win disambiguation
                // (silent dual-bean topology). Asserting exactly one bean catches a Boot-side
                // regression where the condition no longer fires.
                assertThat(ctx.getBeansOfType(JsonMapper::class.java)).hasSize(1)
            }
    }
}
