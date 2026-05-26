package ru.zinin.frigate.analyzer.core.config

import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.ClientCodecConfigurer
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.tcp.SslProvider.SslContextSpec
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.TimeUnit

private const val MAX_IN_MEMORY = 1024 * 1024 * 1024

@Configuration
class WebClientConfiguration(
    val applicationProperties: ApplicationProperties,
) {
    @Bean
    @Primary
    fun httpClient(): HttpClient {
        val sslContext =
            SslContextBuilder
                .forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build()

        return HttpClient
            .create()
//            .wiretap(true)
            .secure { sslContextSpec: SslContextSpec -> sslContextSpec.sslContext(sslContext) }
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, applicationProperties.connectionTimeout.toMillis().toInt())
//            .responseTimeout(applicationProperties.responseTimeout)
            .doOnConnected { conn ->
                conn
                    .addHandlerLast(
                        ReadTimeoutHandler(
                            applicationProperties.readTimeout.toMillis(),
                            TimeUnit.MILLISECONDS,
                        ),
                    ).addHandlerLast(
                        WriteTimeoutHandler(
                            applicationProperties.writeTimeout.toMillis(),
                            TimeUnit.MILLISECONDS,
                        ),
                    )
            }
    }

    /**
     * SNAKE_CASE mapper used **only** for outbound JSON to the detect-server (whose API uses
     * snake_case). Separate from the project's `@Primary` `internalObjectMapper` (camelCase),
     * which governs our own REST wire-format.
     *
     * `.findAndAddModules()` — обязателен: detect-server decoder парсит Kotlin data class'ы
     * (`DetectResponse`, `JobCreatedResponse`, `FrameExtractionResponse`, `JobStatusResponse`).
     * Без `KotlinModule` constructor-based десериализация для required-параметров ломается.
     * Текущий `Builder`-overload справлялся за счёт Spring auto-вызова `findModules()`; при
     * переходе на pre-built `.build()` мы должны явно вызвать `.findAndAddModules()`.
     */
    @Bean
    fun detectServerObjectMapper(): JsonMapper =
        JsonMapper
            .builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .findAndAddModules()
            .build()

    @Bean
    fun detectServerJsonEncoder(
        @Qualifier("detectServerObjectMapper") mapper: JsonMapper,
    ): JacksonJsonEncoder = JacksonJsonEncoder(mapper)

    @Bean
    fun detectServerJsonDecoder(
        @Qualifier("detectServerObjectMapper") mapper: JsonMapper,
    ): JacksonJsonDecoder = JacksonJsonDecoder(mapper)

    @Bean
    fun webClient(
        httpClient: HttpClient,
        @Qualifier("detectServerJsonEncoder") jsonEncoder: JacksonJsonEncoder,
        @Qualifier("detectServerJsonDecoder") jsonDecoder: JacksonJsonDecoder,
    ): WebClient {
        val strategies =
            ExchangeStrategies
                .builder()
                .codecs { codecs: ClientCodecConfigurer ->
                    codecs.defaultCodecs().jacksonJsonEncoder(jsonEncoder)
                    codecs.defaultCodecs().jacksonJsonDecoder(jsonDecoder)
                    codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY)
                }.build()

        return WebClient
            .builder()
            .exchangeStrategies(strategies)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
