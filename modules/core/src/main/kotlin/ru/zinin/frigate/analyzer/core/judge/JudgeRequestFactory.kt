package ru.zinin.frigate.analyzer.core.judge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import ru.zinin.frigate.analyzer.core.storyboard.BuiltStoryboard
import ru.zinin.frigate.analyzer.core.storyboard.StoryboardPreparation

private val logger = KotlinLogging.logger {}

/**
 * Окончательный [JudgeRequest]: крупные кадры, раскадровка события и диагностика того, что из этого уходит модели.
 *
 * Крупные кадры — прежний отбор: первые `max-frames` по ранжированию визуализации, затем по `frameIndex`, с исходными
 * индексами и рамками. Раскадровку judge просит у события ([JudgeCandidate.storyboard]) только при
 * `judge.storyboard.enabled`, со своим бюджетом и без ожидания следующего сегмента. Времена сетки и кадров отсчитаны
 * от одного нуля — начала показанного отрезка ([BuiltStoryboard.zeroSeconds]), без сетки — начала записи; неизвестное
 * время кадра так и остаётся неизвестным.
 *
 * В контекст дописывается `visual_input` ([JudgeContextBuilder.withVisualInput]); база и Telegram здесь не нужны.
 * Без сетки и по её timeout запрос строится по одним кадрам, сбой диагностики оставляет контекст без `visual_input`,
 * не трогая изображений; отмена пробрасывается.
 */
@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class JudgeRequestFactory(
    private val properties: JudgeProperties,
    private val descriptionProperties: DescriptionProperties,
    private val contextBuilder: JudgeContextBuilder,
) {
    suspend fun create(
        candidate: JudgeCandidate,
        context: JudgeContextResult,
    ): JudgeRequest {
        val recording = candidate.recording
        val prepared = prepareStoryboard(candidate)
        val built = prepared.built
        // Один ноль на сетку и кадры: начало показанного отрезка, а без сетки — начало записи.
        val zero = built?.zeroSeconds ?: 0.0
        val offsets = candidate.frames.associate { it.frameIndex to it.offsetSeconds }
        val frames =
            candidate.visualizedFrames
                .take(properties.maxFrames)
                .sortedBy { it.frameIndex }
                .map { DescriptionRequest.FrameImage(it.frameIndex, it.visualizedBytes, offsets[it.frameIndex]?.minus(zero)) }
        val storyboard = built?.storyboard
        val imageCount = frames.size + (if (storyboard == null) 0 else 1)
        logger.info {
            "Judge input: cam=${recording.camId} storyboard=${prepared.status} preparation=${prepared.elapsedMs}ms " +
                "images=$imageCount recording=${recording.id}"
        }
        val visualInput =
            mapOf(
                "frames" to frames.map { mapOf("index" to it.frameIndex, "offset_seconds" to it.offsetSeconds) },
                "storyboard" to storyboardBlock(prepared),
                "preparation_ms" to prepared.elapsedMs,
                "image_count" to imageCount,
            )
        return JudgeRequest(
            recordingId = recording.id,
            camId = recording.camId,
            frames = frames,
            contextJson = contextBuilder.withVisualInput(context, visualInput, recording.id).json,
            language = descriptionProperties.common.language,
            maxSnoozeMinutes = properties.maxSnoozeMinutes,
            storyboard = storyboard,
        )
    }

    /**
     * При выключенном `judge.storyboard.enabled` раскадровку события не трогаем вовсе: первую сборку получило бы и
     * описание, а judge строит её без ожидания следующего сегмента. Нет её у события — `unavailable`, без исключения.
     */
    private suspend fun prepareStoryboard(candidate: JudgeCandidate): PreparedStoryboard {
        val settings = properties.storyboard
        if (!settings.enabled) return PreparedStoryboard(DISABLED)
        val handle = candidate.storyboard ?: return PreparedStoryboard(UNAVAILABLE)
        val preparation = handle.get(settings.timeout, waitForNextSegment = false)
        val status =
            when (preparation.status) {
                StoryboardPreparation.Status.AVAILABLE -> AVAILABLE
                StoryboardPreparation.Status.UNAVAILABLE -> UNAVAILABLE
                StoryboardPreparation.Status.TIMEOUT -> TIMEOUT
            }
        return PreparedStoryboard(status, preparation.built, preparation.elapsed.toMillis())
    }

    /** Параметры сетки — только у построенной; у остальных статусов блок держит один статус. */
    private fun storyboardBlock(prepared: PreparedStoryboard): Map<String, Any?> {
        val built = prepared.built ?: return mapOf("status" to prepared.status)
        val storyboard = built.storyboard
        return mapOf(
            "status" to prepared.status,
            "tiles" to storyboard.tiles,
            "step_seconds" to storyboard.stepSeconds,
            "duration_seconds" to storyboard.durationSeconds,
            "zero_recording_seconds" to built.zeroSeconds,
            "missing_before" to storyboard.missingBefore,
            "missing_after" to storyboard.missingAfter,
        )
    }

    /** Итог для judge: статус `visual_input`, сетка (только у `available`) и время подготовки; 0 — попытки не было. */
    private class PreparedStoryboard(
        val status: String,
        val built: BuiltStoryboard? = null,
        val elapsedMs: Long = 0,
    )

    private companion object {
        const val AVAILABLE = "available"
        const val DISABLED = "disabled"
        const val UNAVAILABLE = "unavailable"
        const val TIMEOUT = "timeout"
    }
}
