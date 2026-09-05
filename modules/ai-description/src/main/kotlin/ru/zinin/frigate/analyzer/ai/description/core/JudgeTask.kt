package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest

object JudgeTask {
    const val SYSTEM_PROMPT =
        "You are the final gate of a home security camera notification system. " +
            "Answer only with the requested JSON object. Do not call tools and do not ask questions."

    const val JSON_SCHEMA =
        """{"type":"object","properties":{"verdict":{"type":"string","enum":["PUBLISH","SUPPRESS"]},""" +
            """"reason":{"type":"string","enum":["NEW_EVENT","CHANGED_SITUATION",""" +
            """"FALSE_POSITIVE","STATIC_OBJECT","DUPLICATE"]},""" +
            """"confidence":{"type":"number"},"summary":{"type":"string"},""" +
            """"snooze_minutes":{"type":"integer"},"wanted":{"type":"string"}},""" +
            """"required":["verdict","reason","summary"],"additionalProperties":false}"""

    fun instructions(request: JudgeRequest): VisionInstructions {
        val language = LanguageNames.of(request.language)
        val preamble =
            "A YOLO detector flagged objects in a short recording from camera `${request.camId}`. Your job is to decide " +
                "whether the household should be notified about this recording. The frames below have the detector's boxes " +
                "drawn on them. Context assembled from the database follows the frames."
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
        return VisionInstructions(SYSTEM_PROMPT, preamble, epilogue, JSON_SCHEMA)
    }
}
