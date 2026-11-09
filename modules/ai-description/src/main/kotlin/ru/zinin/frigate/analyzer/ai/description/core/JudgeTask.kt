package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest

object JudgeTask {
    /** Про отсутствующее правило инструментов — см. [DescriptionTask.SYSTEM_PROMPT]. */
    const val SYSTEM_PROMPT =
        "You are the final gate of a home security camera notification system. " +
            "Answer only with the requested JSON object. Do not ask questions."

    const val JSON_SCHEMA =
        """{"type":"object","properties":{"verdict":{"type":"string","enum":["PUBLISH","SUPPRESS"]},""" +
            """"reason":{"type":"string","enum":["NEW_EVENT","CHANGED_SITUATION",""" +
            """"FALSE_POSITIVE","STATIC_OBJECT","DUPLICATE"]},""" +
            """"confidence":{"type":"number"},"summary":{"type":"string"},""" +
            """"snooze_minutes":{"type":"integer"},"wanted":{"type":"string"}},""" +
            """"required":["verdict","reason","summary"],"additionalProperties":false}"""

    /** Заголовок перед кадрами судьи без раскадровки — тот же, что провайдеры печатали сами до неё. */
    const val FRAMES_HEADER = "Frames (in chronological order):"

    /** Заголовок перед картинками, когда первой идёт раскадровка. */
    const val IMAGES_HEADER = "Images:"

    fun visionRequest(request: JudgeRequest): VisionRequest = VisionRequest(request.recordingId, images(request), instructions(request))

    /** Раскадровка первой, затем кадры с рамками по `frameIndex`; без раскадровки — одни кадры. */
    fun images(request: JudgeRequest): List<VisionImage> {
        val frames = request.frames.sortedBy { it.frameIndex }.map { VisionImage(it.bytes, frameCaption(it)) }
        val storyboard = request.storyboard ?: return frames
        val grid = VisionImage(storyboard.image, StoryboardPromptFormat.caption(storyboard), VisionImage.Role.STORYBOARD)
        return listOf(grid) + frames
    }

    fun instructions(request: JudgeRequest): VisionInstructions {
        val language = LanguageNames.of(request.language)
        val storyboard = request.storyboard
        val intro =
            "A YOLO detector flagged objects in a short recording from camera `${request.camId}`. Your job is to decide " +
                "whether the household should be notified about this recording."
        val preamble =
            if (storyboard == null) {
                "$intro The frames below have the detector's boxes drawn on them. " +
                    "Context assembled from the database follows the frames."
            } else {
                storyboardPreamble(intro, storyboard, hasFrames = request.frames.isNotEmpty())
            }
        val epilogue =
            buildString {
                appendLine("Context (JSON):")
                appendLine("```json")
                appendLine(request.contextJson)
                appendLine("```")
                appendLine()
                appendLine("Decide:")
                appendLine(
                    "- PUBLISH with reason NEW_EVENT when a real, new event is likely: a person, animal or vehicle " +
                        "that is not a known static object and has not been reported recently. Use CHANGED_SITUATION " +
                        "when an ongoing, already reported situation changed materially (another person, a vehicle " +
                        "arrived, someone approached the house).",
                )
                appendLine(
                    "- SUPPRESS with FALSE_POSITIVE when the boxed region is not what the detector claims (glare, " +
                        "foliage, a woodpile, a shadow); with STATIC_OBJECT when the object is real but has been in " +
                        "this spot for a long time (high share of recordings across many days in `static`, identical " +
                        "box across frames); with DUPLICATE when the same situation was already reported " +
                        "(`recent_verdicts`, `last_published`) and nothing new happened.",
                )
                appendLine(
                    "- When in doubt about a person, PUBLISH: missing a real person is worse than one extra message. " +
                        "For vehicles and objects with strong static evidence, lean to SUPPRESS. At night and in " +
                        "infrared be sceptical of odd shapes and glare, but still publish people.",
                )
                appendLine(
                    "- `snooze_minutes` (0–${request.maxSnoozeMinutes}): if this situation will keep producing " +
                        "detections, how long we may skip asking you about this camera while the object classes stay " +
                        "the same and their count does not grow.",
                )
                appendLine(
                    "- `summary`: one sentence in $language, at most 200 characters: what is in the frames and why " +
                        "this verdict.",
                )
                appendLine("- `wanted`: what extra context would have made you confident, or an empty string.")
                appendLine()
                appendLine("Return ONLY this JSON object:")
                append(
                    """{"verdict": "PUBLISH|SUPPRESS", "reason": "...", "confidence": 0.0, """ +
                        """"summary": "...", "snooze_minutes": 0, "wanted": ""}""",
                )
            }
        return VisionInstructions(
            systemPrompt = SYSTEM_PROMPT,
            preamble = preamble,
            imagesHeader = if (storyboard == null) FRAMES_HEADER else IMAGES_HEADER,
            epilogue = epilogue,
            jsonSchema = JSON_SCHEMA,
        )
    }

    /**
     * Общее объяснение сетки и своя фраза о кадрах после неё. Сетка — обзор без рамок, кадры — то, что видел
     * детектор, с его рамками; к ним относятся индексы и рамки контекста. «Full-resolution» из описаний про них
     * неверно: кадры судьи аннотированы и уменьшены. Фраза звучит, только когда кадры есть: задача не обещает
     * картинок, которых не отправляет.
     */
    private fun storyboardPreamble(
        intro: String,
        storyboard: DescriptionRequest.Storyboard,
        hasFrames: Boolean,
    ): String =
        buildString {
            appendLine("$intro Context assembled from the database follows the images.")
            appendLine()
            append(StoryboardPromptFormat.explanation(storyboard))
            if (hasFrames) {
                appendLine()
                append(
                    "The remaining images are frames where the detector found objects, with its boxes drawn on them, " +
                        "captioned with the frame index used in the context and, when known, the frame's time on the " +
                        "storyboard's scale. The storyboard tiles carry no boxes, and a tile labeled \"detection\" is " +
                        "only the tile nearest to a detection in time, not necessarily the frame the detector saw.",
                )
            }
        }

    /** Индекс в подписи всегда — по нему кадр находится в контексте; время — только известное, индекс его не заменяет. */
    private fun frameCaption(frame: DescriptionRequest.FrameImage): String {
        val caption = "Frame ${frame.frameIndex}"
        return frame.offsetSeconds?.let { "$caption at ${StoryboardPromptFormat.seconds(it)}" } ?: caption
    }
}
