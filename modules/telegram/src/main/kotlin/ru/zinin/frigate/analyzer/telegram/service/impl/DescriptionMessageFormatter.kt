package ru.zinin.frigate.analyzer.telegram.service.impl

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionMessageFormatter(
    private val msg: MessageResolver,
) {
    fun captionInitialPlaceholder(
        baseText: String,
        language: String,
        maxLength: Int = MAX_CAPTION_LENGTH,
    ): String {
        val suffix = "\n\n${msg.get(KEY_PLACEHOLDER_SHORT, language)}"
        return "${escapeAndTrim(baseText, maxLength - suffix.length)}$suffix"
    }

    fun captionSuccess(
        baseText: String,
        result: DescriptionResult,
        language: String,
        maxLength: Int = MAX_CAPTION_LENGTH,
    ): String {
        val suffix = "\n\n${htmlEscape(result.short)}"
        return "${escapeAndTrim(baseText, maxLength - suffix.length)}$suffix"
    }

    fun captionFallback(
        baseText: String,
        language: String,
        maxLength: Int = MAX_CAPTION_LENGTH,
    ): String {
        val suffix = "\n\n${msg.get(KEY_FALLBACK, language)}"
        return "${escapeAndTrim(baseText, maxLength - suffix.length)}$suffix"
    }

    fun placeholderShort(language: String): String = msg.get(KEY_PLACEHOLDER_SHORT, language)

    fun placeholderDetailedExpandable(language: String): String =
        "<blockquote expandable>${msg.get(KEY_PLACEHOLDER_DETAILED, language)}</blockquote>"

    fun expandableBlockquoteSuccess(
        result: DescriptionResult,
        language: String,
    ): String = "<blockquote expandable>${htmlEscape(result.detailed)}</blockquote>"

    fun expandableBlockquoteFallback(language: String): String = "<blockquote expandable>${msg.get(KEY_FALLBACK, language)}</blockquote>"

    /**
     * Returns HTML-overhead length for caption when description placeholder is enabled.
     * Used by the sender for truncation budget (see design §6 "Caption 1024-лимит").
     */
    fun captionPlaceholderOverhead(language: String): Int = "\n\n".length + msg.get(KEY_PLACEHOLDER_SHORT, language).length

    /**
     * HTML-escape for Telegram HTML parse mode. Escapes `<`, `>`, `&`.
     * `"` and `'` in text content do NOT need escaping (Telegram HTML only requires it
     * for attribute values, which we never construct).
     */
    private fun htmlEscape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * HTML-aware truncation: escape(text) → fits in budget, return; else trim
     * without breaking an HTML entity. MUST be called on `baseText` when forming
     * a caption — naive substring after escape can split `&amp;`/`&lt;`/`&gt;`
     * and break Telegram HTML.
     */
    private fun escapeAndTrim(
        text: String,
        budget: Int,
    ): String {
        if (budget <= 0) return ""
        val escaped = htmlEscape(text)
        if (escaped.length <= budget) return escaped
        var cutoff = budget - 1 // reserve for ellipsis
        val lastAmp = escaped.lastIndexOf('&', startIndex = (cutoff - 1).coerceAtLeast(0))
        if (lastAmp >= 0) {
            val entityEnd = escaped.indexOf(';', startIndex = lastAmp)
            if (entityEnd < 0 || entityEnd >= cutoff) {
                cutoff = lastAmp
            }
        }
        return escaped.substring(0, cutoff.coerceAtLeast(0)) + "…"
    }

    companion object {
        private const val MAX_CAPTION_LENGTH = 1024
        private const val KEY_PLACEHOLDER_SHORT = "ai.description.placeholder.short"
        private const val KEY_PLACEHOLDER_DETAILED = "ai.description.placeholder.detailed"
        private const val KEY_FALLBACK = "ai.description.fallback.unavailable"
    }
}
