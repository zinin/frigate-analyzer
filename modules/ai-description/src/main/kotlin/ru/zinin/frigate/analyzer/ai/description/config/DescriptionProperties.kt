package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.ai.description")
@Validated
data class DescriptionProperties(
    val enabled: Boolean,
    // Без @NotBlank — при enabled=false provider может быть пустым в конфиге.
    // Валидация provider происходит в AiDescriptionAutoConfiguration:
    // если enabled=true и нет бина под provider — WARN.
    val provider: String,
    @field:Valid
    val common: CommonSection,
) {
    data class CommonSection(
        @field:Pattern(regexp = "ru|en", message = "must be 'ru' or 'en'")
        val language: String,
        @field:Min(50) @field:Max(500)
        val shortMaxLength: Int,
        @field:Min(200) @field:Max(3500)
        val detailedMaxLength: Int,
        @field:Min(1) @field:Max(50)
        val maxFrames: Int,
        /**
         * Длинная сторона кадра в пикселях перед отправкой модели; `0` — отдавать как есть.
         * Кадры приходят в разрешении камеры, а vision-эндпоинты часто режут по длинной стороне
         * (гейт LiteLLM молча выбрасывает картинку больше 1568 px) и всегда считают токены по
         * площади. Уменьшение делается один раз на запрос, до попыток провайдера.
         */
        @field:Min(0) @field:Max(8192)
        val maxImageSide: Int = 0,
        val queueTimeout: Duration,
        val timeout: Duration,
        @field:Min(1) @field:Max(10)
        val maxConcurrent: Int,
        // Дефолт RateLimit() (enabled=false) нужен для unit-тестов, которые
        // конструируют CommonSection(...) напрямую без YAML-binding-а — лимитер
        // при этом «прозрачен» (tryAcquire() всегда true). В production binding
        // всегда перезаписывает через application.yaml.
        @field:Valid
        val rateLimit: RateLimit = RateLimit(),
    ) {
        init {
            require(maxImageSide == 0 || maxImageSide >= 256) {
                "max-image-side must be 0 (disabled) or at least 256, was $maxImageSide"
            }
            require(queueTimeout.toMillis() > 0) { "queue-timeout must be positive" }
            require(timeout.toMillis() > 0) { "timeout must be positive" }
        }
    }

    data class RateLimit(
        val enabled: Boolean = false,
        @field:Min(1) @field:Max(10000)
        val maxRequests: Int = 10,
        val window: Duration = Duration.ofHours(1),
    ) {
        init {
            require(window.toMillis() > 0) { "rate-limit.window must be positive" }
        }
    }
}
