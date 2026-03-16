package ru.zinin.frigate.analyzer.telegram.config

import dev.inmo.kslog.common.LogLevel
import dev.inmo.kslog.common.filter.FilterKSLog
import dev.inmo.tgbotapi.bot.TelegramBot
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
        suppressLongPollingTimeoutErrors()
        logger.info { "Telegram bot module is loaded" }
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
                    proxy =
                        java.net.Proxy(
                            java.net.Proxy.Type.SOCKS,
                            java.net.InetSocketAddress(proxyConfig.host, proxyConfig.port),
                        )
                }
            }
        }
    }

    /**
     * Suppresses ERROR-level logging of HttpRequestTimeoutException in ktgbotapi.
     *
     * DefaultKtorRequestsExecutor.execute() uses runCatchingLogging which logs
     * HttpRequestTimeoutException at ERROR before the long polling loop can silently
     * skip it via autoSkipTimeoutExceptions. This is a known library issue:
     * https://github.com/InsanusMokrassar/ktgbotapi/issues/1027
     */
    private fun suppressLongPollingTimeoutErrors() {
        DefaultKTgBotAPIKSLog =
            FilterKSLog(DefaultKTgBotAPIKSLog) { level, _, throwable ->
                !(level == LogLevel.ERROR && isTimeoutException(throwable))
            }
    }

    private fun isTimeoutException(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        if (throwable is HttpRequestTimeoutException) return true
        return isTimeoutException(throwable.cause)
    }
}
