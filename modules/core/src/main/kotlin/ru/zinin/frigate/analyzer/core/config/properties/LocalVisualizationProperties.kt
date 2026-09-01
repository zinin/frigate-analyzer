package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "application.local-visualization")
@Validated
data class LocalVisualizationProperties(
    @field:Min(1)
    val lineWidth: Int = 2,
    @field:Min(1)
    @field:Max(100)
    val quality: Int = 90,
    @field:Min(1)
    val referenceHeight: Int = 720,
    @field:Min(0)
    val minFontScale: Float = 0.5f,
    @field:Min(0)
    val maxFontScale: Float = 2.2f,
    @field:Min(0)
    val baseFontScale: Float = 2.0f,
    @field:Min(1)
    val baseFontSize: Int = 16,
    @field:Min(0)
    val labelPadding: Int = 4,
    /**
     * Потолок — предел медиа в одном rich-сообщении Telegram (50), а не наш дефолт: кадры
     * сверх него в уведомление физически не попадут. Планка стояла на 10 и делала стартовой ошибкой
     * значение, легальное в предыдущей версии, а заодно через `minOf` в `RecordingProcessingFacade`
     * молча обнуляла верхнюю половину независимой настройки `APP_AI_DESCRIPTION_MAX_FRAMES`.
     */
    @field:Min(1)
    @field:Max(50)
    val maxFrames: Int = 10,
)
