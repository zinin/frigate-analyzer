package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.util.Locale

/**
 * Раскадровка в промпте, одинаково для описаний и судьи: подпись сетки, объяснение, как её читать, и формат
 * секунд. Политики задачи здесь нет. Нет и фразы о картинках после сетки: у описаний это полноразмерные кадры,
 * у судьи — кадры детектора с рамками, поэтому её пишет каждая задача сама.
 */
internal object StoryboardPromptFormat {
    fun caption(storyboard: DescriptionRequest.Storyboard): String =
        "Storyboard: ${storyboard.tiles} frames, ${seconds(storyboard.stepSeconds)} apart, left to right, top to bottom"

    /**
     * Число клеток, шаг, длительность и порядок чтения, шкала времени, детекции со своим временем и запись,
     * которой нет до или после. Строки разделены `\n`, в конце перевода строки нет: что идёт следом, решает задача.
     */
    fun explanation(storyboard: DescriptionRequest.Storyboard): String =
        buildList {
            add(
                "The first image is a storyboard: ${storyboard.tiles} frames taken every ${seconds(storyboard.stepSeconds)} " +
                    "over ${seconds(storyboard.durationSeconds)} of footage, read left to right, top to bottom. " +
                    "Each tile is labeled with its time in seconds from the start of the footage; " +
                    "tiles labeled \"detection\" are the ones closest to a detection.",
            )
            if (storyboard.detections.isNotEmpty()) add("The detector found: ${detectionsLine(storyboard.detections)}.")
            if (storyboard.missingBefore) add("Earlier footage is not available.")
            if (storyboard.missingAfter) add("Footage after ${seconds(storyboard.durationSeconds)} is not available.")
        }.joinToString("\n")

    /** `5.0s`: одна цифра после точки, точка при любой локали JVM. */
    fun seconds(value: Double): String = String.format(Locale.US, "%.1fs", value)

    private fun detectionsLine(marks: List<DescriptionRequest.DetectionMark>): String =
        marks.joinToString("; ") { mark ->
            val base = "${mark.className} ${String.format(Locale.US, "%.2f", mark.confidence)}"
            mark.offsetSeconds?.let { "$base at ${seconds(it)}" } ?: base
        }
}
