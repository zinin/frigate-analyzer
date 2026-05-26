package ru.zinin.frigate.analyzer.core.config

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.config.WebFluxConfigurer
import tools.jackson.databind.json.JsonMapper

/**
 * Wires the project's `@Primary internalObjectMapper` (configured in [JacksonConfiguration])
 * into WebFlux's REST JSON codec.
 *
 * **Honest narrative про duplication:** Spring Boot 4's `CodecsAutoConfiguration.jacksonCodecCustomizer`
 * автоматически wire'ит любой `JsonMapper` бин (включая наш `@Primary`) в WebFlux codec через
 * `CodecCustomizer`. Наш configurer **функционально дублирует** эту логику. Поведение совпадает,
 * поэтому behavioral test (запрос -> отвечает с ISO-8601 / толерантно к unknown props) пройдёт
 * обоими путями — это НЕ regression guard на конкретно наш configurer.
 *
 * **Ценность configurer'а — архитектурная, не поведенческая:**
 *  - Explicit ownership statement (closes #29 требует «config truly governs wire-format»).
 *  - Belt-and-suspenders: `@Order(Ordered.LOWEST_PRECEDENCE)` страхует если Boot's auto-config
 *    изменит wiring в minor-release.
 *  - Документирует ownership wire-format в одном явном файле.
 *
 * **Regression guard** — `WebFluxJacksonCodecConfigurerTest` (unit, identity check через
 * публичный `JacksonCodecSupport.getMapper()` — codec'ы построены на нашем mapper'е) +
 * `JacksonConfigurationTest.ApplicationContextRunner` (bean topology с auto-config).
 *
 * **`@Component` вместо `@Configuration`:** класс не объявляет `@Bean`-методов, поэтому
 * `@Configuration` (CGLIB-proxy + full config scanning) — overhead без выигрыша.
 *
 * **Constructor parameter `JsonMapper`** (не `ObjectMapper`): Spring 7 codec API принимает
 * `JsonMapper` или `JsonMapper.Builder`; мы передаём pre-built `JsonMapper` для явного
 * контроля над wire-format (теряя Boot's `JsonMapperBuilderCustomizer`-ы — осознанный
 * trade-off, см. [JacksonConfiguration] KDoc).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class WebFluxJacksonCodecConfigurer(
    private val internalObjectMapper: JsonMapper,
) : WebFluxConfigurer {
    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(internalObjectMapper))
        configurer.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(internalObjectMapper))
    }
}
