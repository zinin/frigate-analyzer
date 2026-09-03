package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.core.LanguageNames

/**
 * Текстовые части промпта для Grok. Кадры идут отдельными image-блоками между [frameLabel]-ами,
 * см. [GrokPromptFileWriter]. Ответ приходит через `--json-schema`, поэтому правила говорят о полях
 * structured output, а не о JSON в тексте.
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
            appendLine("Fill the structured output fields \"short\" and \"detailed\".")
            appendLine("Rules:")
            appendLine("- \"short\" must not exceed $shortMaxLength characters.")
            appendLine("- \"detailed\" must not exceed $detailedMaxLength characters.")
            append("- No markdown, no explanations.")
        }

    companion object {
        /** Для `--system-prompt-override`: вместо стандартного промпта кодового агента. */
        const val SYSTEM_PROMPT =
            "You describe frames from a security camera for a notification message. " +
                "Answer only through the structured output. Do not call tools and do not ask questions."
    }
}
