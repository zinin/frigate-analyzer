package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.ai.judge")
@Validated
data class JudgeProperties(
    val enabled: Boolean = false,
    val defaultPreset: String = "",
    val queueTimeout: Duration = Duration.ofSeconds(30),
    val timeout: Duration = Duration.ofSeconds(60),
    @field:Min(1) @field:Max(10)
    val maxConcurrent: Int = 2,
    @field:Min(1) @field:Max(10)
    val maxFrames: Int = 4,
    @field:Min(0) @field:Max(8192)
    val maxImageSide: Int = 1280,
    @field:Valid
    val rateLimit: DescriptionProperties.RateLimit =
        DescriptionProperties.RateLimit(enabled = true, maxRequests = 200, window = Duration.ofHours(1)),
    val maxSnooze: Duration = Duration.ofMinutes(30),
    val staticWindow: Duration = Duration.ofDays(7),
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val staticIou: Double = 0.4,
    val historyWindow: Duration = Duration.ofHours(6),
    @field:Min(1) @field:Max(50)
    val historyLimit: Int = 10,
    /** Пусто = зона владельца из Telegram, затем зона JVM. */
    val zone: String = "",
    val cameras: Map<String, CameraSection> = emptyMap(),
    /**
     * Потолок кандидатов, которых судья держит в памяти одновременно, на все камеры сразу.
     *
     * Предохранитель памяти, а не регулятор пропускной способности: кандидат сверх потолка уходит
     * получателям неосуждённым, как и переполнение очереди камеры. Считать его нужно по кадрам —
     * каждый кандидат держит и визуализированные кадры, и оригиналы, удерживаемые замыканием
     * supplier-а описания: на 4K-камере это единицы, а не два мегабайта.
     */
    @field:Min(1) @field:Max(512)
    val maxInFlight: Int = 32,
) {
    data class CameraSection(
        val notes: String = "",
    )

    init {
        require(queueTimeout.toMillis() > 0) { "application.ai.judge.queue-timeout must be positive" }
        require(timeout.toMillis() > 0) { "application.ai.judge.timeout must be positive" }
        require(maxSnooze >= Duration.ofMinutes(1)) {
            "application.ai.judge.max-snooze must be at least PT1M: the ceiling is whole minutes"
        }
        require(!staticWindow.isNegative && !staticWindow.isZero) { "application.ai.judge.static-window must be positive" }
        require(!historyWindow.isNegative && !historyWindow.isZero) { "application.ai.judge.history-window must be positive" }
        require(maxImageSide == 0 || maxImageSide >= 256) { "application.ai.judge.max-image-side must be 0 or at least 256" }
        require(staticIou in 0.0..1.0) { "application.ai.judge.static-iou must be between 0.0 and 1.0" }
        require(zone.isBlank() || runCatching { java.time.ZoneId.of(zone) }.isSuccess) {
            "application.ai.judge.zone '$zone' is not a valid zone id"
        }
    }

    /**
     * Потолок, который уезжает модели в промпт и режет её ответ. Округления вверх здесь нет
     * намеренно: оно делало бы потолок ВЫШЕ заданного (`PT30S` давал минуту), то есть настройка,
     * задуманная ограничивать, тихо поднимала бы себя. Что значение не меньше минуты, гарантирует
     * [init] — иначе целая минута была бы недостижима и судья обещал бы модели диапазон `0–0`.
     */
    val maxSnoozeMinutes: Int get() = maxSnooze.toMinutes().toInt()
}
