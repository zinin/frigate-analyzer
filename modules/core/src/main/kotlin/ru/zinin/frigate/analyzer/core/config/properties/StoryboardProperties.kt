package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Раскадровка для AI-описаний: сетка кадров вокруг детекции, при необходимости из соседних
 * сегментов. Модуль ai-description про неё не знает — настройки читает `core`.
 */
@ConfigurationProperties(prefix = "application.ai.description.storyboard")
@Validated
data class StoryboardProperties(
    /** `false` — описание по одним полноразмерным кадрам, как до раскадровки (с подписями времени). */
    val enabled: Boolean = true,
    /** Сколько захватить до первой детекции. Не больше 10 с: соседний сегмент берётся только один. */
    val before: Duration = Duration.ofSeconds(5),
    /** Сколько захватить после последней детекции, тот же предел. */
    val after: Duration = Duration.ofSeconds(5),
    @field:Min(4)
    @field:Max(25)
    val tiles: Int = 16,
    /** Сколько ждать следующий сегмент сверх ожидаемого момента его появления. */
    val nextSegmentWait: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!before.isNegative && before <= MAX_MARGIN) { "storyboard.before must be within 0..$MAX_MARGIN, was $before" }
        require(!after.isNegative && after <= MAX_MARGIN) { "storyboard.after must be within 0..$MAX_MARGIN, was $after" }
        require(!nextSegmentWait.isNegative && nextSegmentWait <= MAX_WAIT) {
            "storyboard.next-segment-wait must be within 0..$MAX_WAIT, was $nextSegmentWait"
        }
    }

    companion object {
        val MAX_MARGIN: Duration = Duration.ofSeconds(10)
        val MAX_WAIT: Duration = Duration.ofSeconds(120)
    }
}
