package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.util.Locale

/** Тексты задачи описаний. Единственное место, где живут формулировки для обоих провайдеров. */
object DescriptionTask {
    /**
     * Правила про инструменты здесь нет намеренно. Кадры до провайдеров доезжают по-разному:
     * Claude получает ссылки `@path` и видит картинку только вызовом Read, Grok — готовые
     * image-блоки, а инструменты ему отключены флагами команды. Общий запрет запрещал Claude
     * единственный способ увидеть кадр, поэтому каждый бэкенд дописывает своё правило сам.
     */
    const val SYSTEM_PROMPT =
        "You describe frames from a security camera for a notification message. " +
            "Answer only with the requested JSON object. Do not ask questions."

    const val JSON_SCHEMA =
        """{"type":"object","properties":{"short":{"type":"string"},"detailed":{"type":"string"}},"required":["short","detailed"],"additionalProperties":false}"""

    /** Заголовок перед кадрами, когда модель получает только их. */
    const val FRAMES_HEADER = "Frames (in chronological order):"

    fun visionRequest(request: DescriptionRequest): VisionRequest =
        VisionRequest(request.recordingId, images(request), instructions(request))

    /** Кадры по времени, каждый со своей подписью. */
    fun images(request: DescriptionRequest): List<VisionImage> =
        request.frames
            .sortedBy { it.frameIndex }
            .map { frame -> VisionImage(frame.bytes, frameCaption(frame)) }

    fun instructions(request: DescriptionRequest): VisionInstructions {
        val languageName = LanguageNames.of(request.language)
        val preamble =
            buildString {
                appendLine("You are analyzing surveillance camera frames captured during an object detection event.")
                append("Write both descriptions in $languageName.")
            }
        val epilogue =
            buildString {
                appendLine("Return ONLY this JSON object (no prose around it):")
                appendLine("""{"short": "...", "detailed": "..."}""")
                appendLine()
                appendLine("Rules:")
                appendLine(
                    "- \"short\": one to three sentences on what happened, including any movement " +
                        "(who or what, where from, where to, whether it stopped); " +
                        "must not exceed ${request.shortMaxLength} characters.",
                )
                appendLine("- \"detailed\" must not exceed ${request.detailedMaxLength} characters.")
                append("- No markdown, no explanations — just the JSON object.")
            }
        return VisionInstructions(
            systemPrompt = SYSTEM_PROMPT,
            preamble = preamble,
            imagesHeader = FRAMES_HEADER,
            epilogue = epilogue,
            jsonSchema = JSON_SCHEMA,
        )
    }

    private fun frameCaption(frame: DescriptionRequest.FrameImage): String =
        frame.offsetSeconds?.let { "Frame at ${seconds(it)}" } ?: "Frame ${frame.frameIndex}"

    /** `5.0s`: одна цифра после точки, точка при любой локали JVM. */
    internal fun seconds(value: Double): String = String.format(Locale.US, "%.1fs", value)
}
