package ru.zinin.frigate.analyzer.core.config

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.http.codec.json.JacksonJsonEncoder
import ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappers
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper

class WebFluxJacksonCodecConfigurerTest {
    @Test
    fun `configureHttpMessageCodecs registers Jackson encoder and decoder built from our mapper`() {
        val mapper = TestObjectMappers.internalMapper()
        val configurer = WebFluxJacksonCodecConfigurer(mapper)

        val serverCodecConfigurer = mockk<ServerCodecConfigurer>(relaxed = true)
        val defaultCodecs = mockk<ServerCodecConfigurer.ServerDefaultCodecs>(relaxed = true)
        every { serverCodecConfigurer.defaultCodecs() } returns defaultCodecs

        val encoderSlot = slot<JacksonJsonEncoder>()
        val decoderSlot = slot<JacksonJsonDecoder>()
        every { defaultCodecs.jacksonJsonEncoder(capture(encoderSlot)) } just Runs
        every { defaultCodecs.jacksonJsonDecoder(capture(decoderSlot)) } just Runs

        configurer.configureHttpMessageCodecs(serverCodecConfigurer)

        verify(exactly = 1) { defaultCodecs.jacksonJsonEncoder(any()) }
        verify(exactly = 1) { defaultCodecs.jacksonJsonDecoder(any()) }
        assertThat(encoderSlot.captured).isNotNull
        assertThat(decoderSlot.captured).isNotNull

        // IDENTITY GUARD: убедиться что codec'ы построены ИМЕННО на нашем mapper'е, а не
        // на дефолтных. Без этого тест прошёл бы при баге, где configureHttpMessageCodecs
        // создаёт новые JsonMapper.builder().build() вместо использования параметра.
        //
        // Spring 7 экспонирует публичный `JacksonCodecSupport.getMapper(): T` (родитель
        // `JacksonJsonEncoder`/`JacksonJsonDecoder` через `AbstractJacksonEncoder`/
        // `AbstractJacksonDecoder`). Это снимает необходимость в reflection: plan KDoc
        // предусматривал «если Spring добавит public getMapper() — заменить на прямой
        // вызов»; в Spring 7.0.7 это уже сделано, поэтому используем публичный API.
        assertThat(encoderSlot.captured.mapper).isSameAs(mapper)
        assertThat(decoderSlot.captured.mapper).isSameAs(mapper)
    }

    @Test
    fun `Spring DI picks @Primary internalObjectMapper even when other JsonMapper beans exist`() {
        // Topology guard: in production there are TWO JsonMapper beans —
        // `@Primary internalObjectMapper` (camelCase, REST wire-format) и qualified
        // `detectServerObjectMapper` (SNAKE_CASE, detect-server outbound). Spring must inject
        // the @Primary one into `WebFluxJacksonCodecConfigurer`; otherwise REST endpoints
        // would silently serialize via SNAKE_CASE, breaking external camelCase contract.
        //
        // We simulate this by registering a second SNAKE_CASE JsonMapper alongside our
        // configuration and asserting the configurer's captured encoder mapper is the
        // @Primary one (not the SNAKE_CASE alternative). A regression where someone removes
        // `@Primary` would surface here as `NoUniqueBeanDefinitionException` (constructor
        // injection can't disambiguate) — also a valid failure mode.
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
            .withUserConfiguration(
                JacksonConfiguration::class.java,
                WebFluxJacksonCodecConfigurer::class.java,
            ).withBean(
                "snakeCaseMapperLike",
                JsonMapper::class.java,
                {
                    JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
                },
            ).run { ctx ->
                val configurer = ctx.getBean(WebFluxJacksonCodecConfigurer::class.java)
                val expectedMapper = ctx.getBean("internalObjectMapper", JsonMapper::class.java)

                val serverCodecConfigurer = mockk<ServerCodecConfigurer>(relaxed = true)
                val defaultCodecs = mockk<ServerCodecConfigurer.ServerDefaultCodecs>(relaxed = true)
                every { serverCodecConfigurer.defaultCodecs() } returns defaultCodecs

                val encoderSlot = slot<JacksonJsonEncoder>()
                val decoderSlot = slot<JacksonJsonDecoder>()
                every { defaultCodecs.jacksonJsonEncoder(capture(encoderSlot)) } just Runs
                every { defaultCodecs.jacksonJsonDecoder(capture(decoderSlot)) } just Runs

                configurer.configureHttpMessageCodecs(serverCodecConfigurer)

                assertThat(encoderSlot.captured.mapper).isSameAs(expectedMapper)
                assertThat(decoderSlot.captured.mapper).isSameAs(expectedMapper)
            }
    }
}
