package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest

/** Тексты задачи описаний, одни для обоих провайдеров; объяснение раскадровки общее с судьёй — [StoryboardPromptFormat]. */
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

    /** Заголовок перед картинками, когда первой идёт раскадровка. */
    const val IMAGES_HEADER = "Images:"

    fun visionRequest(request: DescriptionRequest): VisionRequest =
        VisionRequest(request.recordingId, images(request), instructions(request))

    /** Раскадровка первой, затем полноразмерные кадры по времени; без раскадровки — одни кадры. */
    fun images(request: DescriptionRequest): List<VisionImage> {
        val frames = request.frames.sortedBy { it.frameIndex }
        val storyboard = request.storyboard ?: return frames.map { VisionImage(it.bytes, frameCaption(it)) }
        val grid = VisionImage(storyboard.image, StoryboardPromptFormat.caption(storyboard), VisionImage.Role.STORYBOARD)
        return listOf(grid) + frames.map { VisionImage(it.bytes, fullFrameCaption(it)) }
    }

    fun instructions(request: DescriptionRequest): VisionInstructions {
        val languageName = LanguageNames.of(request.language)
        val storyboard = request.storyboard
        val preamble =
            if (storyboard == null) {
                buildString {
                    appendLine("You are analyzing surveillance camera frames captured during an object detection event.")
                    append("Write both descriptions in $languageName.")
                }
            } else {
                storyboardPreamble(storyboard, languageName, hasFrames = request.frames.isNotEmpty())
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
            imagesHeader = if (storyboard == null) FRAMES_HEADER else IMAGES_HEADER,
            epilogue = epilogue,
            jsonSchema = JSON_SCHEMA,
        )
    }

    private fun storyboardPreamble(
        storyboard: DescriptionRequest.Storyboard,
        languageName: String,
        hasFrames: Boolean,
    ): String =
        buildString {
            appendLine("You are analyzing surveillance camera footage around an object detection event.")
            appendLine("Write both descriptions in $languageName.")
            appendLine()
            appendLine(StoryboardPromptFormat.explanation(storyboard))
            if (hasFrames) appendLine("The remaining images are full-resolution frames for details.")
            appendLine()
            append(
                "Describe how the scene develops over time: where objects come from, where they move, " +
                    "whether they stop or leave. An object that stays in the same place in every tile is stationary.",
            )
        }

    private fun fullFrameCaption(frame: DescriptionRequest.FrameImage): String =
        frame.offsetSeconds?.let { "Full-resolution frame at ${StoryboardPromptFormat.seconds(it)}" }
            ?: "Full-resolution frame ${frame.frameIndex}"

    private fun frameCaption(frame: DescriptionRequest.FrameImage): String =
        frame.offsetSeconds?.let { "Frame at ${StoryboardPromptFormat.seconds(it)}" } ?: "Frame ${frame.frameIndex}"
}
