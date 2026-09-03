package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.core.LanguageNames

/**
 * Текстовые части промпта для Grok. Кадры идут отдельными image-блоками между [frameLabel]-ами,
 * см. [GrokPromptFileWriter]. Правила просят JSON-объект текстом, а не «заполни structured output»:
 * xAI-модели всё равно отдают его в `structuredOutput` по `--json-schema`, а BYOK-эндпоинты, которые
 * схему не применяют или не принимают, кладут тот же объект в `text` — [GrokOutputParser] читает оба.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokPromptBuilder {
    fun introduction(language: String): String =
        buildString {
            appendLine("You are analyzing surveillance camera frames captured during an object detection event.")
            appendLine("Write both descriptions in ${LanguageNames.of(language)}.")
            appendLine()
            append("Frames (in chronological order):")
        }

    fun frameLabel(frameIndex: Int): String = "Frame $frameIndex:"

    fun rules(
        shortMaxLength: Int,
        detailedMaxLength: Int,
    ): String =
        buildString {
            appendLine("Return ONLY this JSON object (no prose around it):")
            appendLine("""{"short": "...", "detailed": "..."}""")
            appendLine()
            appendLine("Rules:")
            appendLine("- \"short\" must not exceed $shortMaxLength characters.")
            appendLine("- \"detailed\" must not exceed $detailedMaxLength characters.")
            append("- No markdown, no explanations - just the JSON object.")
        }

    companion object {
        /** Для `--system-prompt-override`: вместо стандартного промпта кодового агента. */
        const val SYSTEM_PROMPT =
            "You describe frames from a security camera for a notification message. " +
                "Answer only with the requested JSON object. Do not call tools and do not ask questions."
    }
}
