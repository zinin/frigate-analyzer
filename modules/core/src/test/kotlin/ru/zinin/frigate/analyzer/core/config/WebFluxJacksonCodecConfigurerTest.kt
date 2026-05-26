package ru.zinin.frigate.analyzer.core.config

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.http.codec.json.JacksonJsonEncoder
import ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappers

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
}
