package ru.zinin.frigate.analyzer.telegram.config

import dev.inmo.kslog.common.filter.FilterKSLog
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.CommonBotException
import dev.inmo.tgbotapi.extensions.api.telegramBot
import dev.inmo.tgbotapi.utils.DefaultKTgBotAPIKSLog
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.HttpRequestTimeoutException
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan

private val logger = KotlinLogging.logger {}

@AutoConfiguration
@EnableConfigurationProperties(TelegramProperties::class)
@ComponentScan("ru.zinin.frigate.analyzer.telegram")
class TelegramAutoConfiguration {
    init {
        logger.info { "Telegram bot module is loaded" }
        suppressLongPollingTimeoutErrors()
    }

    @Bean
    @ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
    fun telegramBot(properties: TelegramProperties): TelegramBot {
        val proxyConfig = properties.proxy?.takeIf { it.host.isNotBlank() }

        if (proxyConfig != null) {
            logger.info { "Telegram bot using SOCKS5 proxy: ${proxyConfig.host}:${proxyConfig.port}" }
        }

        return telegramBot(properties.botToken) {
            engine {
                if (proxyConfig != null) {
                    proxy = java.net.Proxy(
                        java.net.Proxy.Type.SOCKS,
                        java.net.InetSocketAddress(proxyConfig.host, proxyConfig.port),
                    )
                }
            }
        }
    }

    private fun suppressLongPollingTimeoutErrors() {
        DefaultKTgBotAPIKSLog =
            FilterKSLog(DefaultKTgBotAPIKSLog) { _, _, throwable ->
                !isTimeoutException(throwable)
            }
    }

    private fun isTimeoutException(throwable: Throwable?): Boolean =
        throwable is HttpRequestTimeoutException ||
            (throwable is CommonBotException && throwable.cause is HttpRequestTimeoutException)
}
