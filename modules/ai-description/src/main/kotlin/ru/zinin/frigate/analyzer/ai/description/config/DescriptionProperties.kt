package ru.zinin.frigate.analyzer.ai.description.config

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

private val logger = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "application.ai.description")
@Validated
data class DescriptionProperties(
    val enabled: Boolean,
    // Без @NotBlank — при enabled=false provider может быть пустым в конфиге.
    // Валидация provider происходит в AiDescriptionAutoConfiguration:
    // если enabled=true и нет бина под provider — WARN.
    // Legacy-путь: используется, только когда карта presets пуста.
    val provider: String,
    @field:Valid
    val common: CommonSection,
    /** Активный пресет по умолчанию; пусто = первый годный пресет каталога. */
    val defaultPreset: String = "",
    /**
     * Ключ карты — id пресета. Пустая карта означает legacy-путь: один пресет синтезируется из
     * [provider] и секции провайдера. Валидация здесь, а не через `@field:Valid`: значения карты
     * jakarta-валидатор не обходит, а сообщение с id пресета читается лучше стектрейса.
     */
    val presets: Map<String, Preset> = emptyMap(),
) {
    init {
        presets.forEach { (id, preset) ->
            require(PRESET_ID.matches(id)) { "preset id '$id' must match ${PRESET_ID.pattern}" }
            preset.validate(id)
        }
        if (presets.isEmpty()) {
            // Миграция «сначала env, потом yaml»: default-preset выставляют в
            // docker/deploy/.env.example раньше, чем объявляют карту в смонтированном yaml.
            // Ссылаться такому имени пока не на что, но ронять старт нельзя — иначе
            // документированный порядок миграции невыполним.
            if (defaultPreset.isNotBlank()) {
                logger.warn { "default-preset '$defaultPreset' has no effect until presets are declared" }
            }
        } else {
            require(defaultPreset.isBlank() || presets.containsKey(defaultPreset)) {
                "default-preset '$defaultPreset' is not declared in presets: ${presets.keys.joinToString()}"
            }
        }
    }

    data class Preset(
        val provider: String,
        val model: String,
        val effort: String = "",
    ) {
        internal fun validate(id: String) {
            require(provider in KNOWN_PROVIDERS) {
                "preset '$id': provider '$provider' is unknown (known: ${KNOWN_PROVIDERS.joinToString()})"
            }
            require(model.isNotBlank()) { "preset '$id': model must not be blank" }
            require(effort.isBlank() || effort in EFFORTS) {
                "preset '$id': effort '$effort' must be empty or one of ${EFFORTS.joinToString()}"
            }
            require(effort.isBlank() || provider == "grok") {
                "preset '$id': effort is supported only by provider grok, not '$provider'"
            }
        }
    }

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
        val maxRequests: Int = 30,
        val window: Duration = Duration.ofHours(1),
    ) {
        init {
            require(window.toMillis() > 0) { "rate-limit.window must be positive" }
        }
    }

    companion object {
        val KNOWN_PROVIDERS = listOf("claude", "grok")
        private val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
        private val PRESET_ID = Regex("[a-z0-9][a-z0-9-]{0,31}")
    }
}
