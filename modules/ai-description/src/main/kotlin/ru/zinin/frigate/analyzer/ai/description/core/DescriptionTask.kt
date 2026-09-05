package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest

/** Тексты задачи описаний. Единственное место, где живут формулировки для обоих провайдеров. */
object DescriptionTask {
    const val SYSTEM_PROMPT =
        "You describe frames from a security camera for a notification message. " +
            "Answer only with the requested JSON object. Do not call tools and do not ask questions."

    const val JSON_SCHEMA =
        """{"type":"object","properties":{"short":{"type":"string"},"detailed":{"type":"string"}},"required":["short","detailed"],"additionalProperties":false}"""

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
                appendLine("- \"short\" must not exceed ${request.shortMaxLength} characters.")
                appendLine("- \"detailed\" must not exceed ${request.detailedMaxLength} characters.")
                append("- No markdown, no explanations — just the JSON object.")
            }
        return VisionInstructions(SYSTEM_PROMPT, preamble, epilogue, JSON_SCHEMA)
    }
}
