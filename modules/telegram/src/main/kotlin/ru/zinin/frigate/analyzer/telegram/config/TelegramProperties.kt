package ru.zinin.frigate.analyzer.telegram.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

data class ProxyProperties(
    val host: String = "",
    val port: Int = 1080,
)

@ConfigurationProperties(prefix = "application.telegram")
@Validated
data class TelegramProperties(
    val enabled: Boolean = false,
    @field:NotBlank(message = "Telegram bot token must not be blank")
    val botToken: String,
    @field:NotBlank(message = "Telegram owner username must not be blank")
    val owner: String,
    @field:Min(1)
    val queueCapacity: Int = 100,
    val sendVideoTimeout: Duration = Duration.ofMinutes(3),
    val proxy: ProxyProperties? = null,
)
