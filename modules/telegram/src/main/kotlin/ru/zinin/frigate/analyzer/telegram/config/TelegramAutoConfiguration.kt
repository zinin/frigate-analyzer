package ru.zinin.frigate.analyzer.telegram.config

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.telegramBot
import io.github.oshai.kotlinlogging.KotlinLogging
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
    }

    @Bean
    @ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
    fun telegramBot(properties: TelegramProperties): TelegramBot = telegramBot(properties.botToken)
}
