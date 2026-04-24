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

    /**
     * HTML-escaped + entity-aware trimmed baseText WITHOUT any AI placeholder suffix.
     * Used by album first-photo caption and the empty-frame branch — both paths have no
     * editable caption target, so a placeholder would stay stuck forever.
     */
    fun captionBasePlain(
        baseText: String,
        maxLength: Int = MAX_CAPTION_LENGTH,
    ): String = escapeAndTrim(baseText, maxLength)

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
        "$BLOCKQUOTE_OPEN${msg.get(KEY_PLACEHOLDER_DETAILED, language)}$BLOCKQUOTE_CLOSE"

    fun expandableBlockquoteSuccess(
        result: DescriptionResult,
        language: String,
    ): String = "$BLOCKQUOTE_OPEN${htmlEscape(result.detailed)}$BLOCKQUOTE_CLOSE"

    fun expandableBlockquoteFallback(language: String): String = "$BLOCKQUOTE_OPEN${msg.get(KEY_FALLBACK, language)}$BLOCKQUOTE_CLOSE"

    /**
     * Builds the combined media-group reply text (short + expandable blockquote with detailed),
     * bounded to Telegram's 4096-char `editMessageText` limit. When the detailed portion does
     * not fit, it is entity-aware truncated so the `<blockquote>` wrapper is never broken.
     */
    fun mediaGroupText(
        baseText: String,
        outcome: Result<DescriptionResult>,
        language: String,
    ): String {
        val short =
            outcome.fold(
                onSuccess = { captionSuccess(baseText, it, language) },
                onFailure = { captionFallback(baseText, language) },
            )
        val detailedRaw =
            outcome.fold(
                onSuccess = { it.detailed },
                onFailure = { msg.get(KEY_FALLBACK, language) },
            )
        val wrapperOverhead = BLOCKQUOTE_OPEN.length + BLOCKQUOTE_CLOSE.length
        val detailedBudget = (MAX_EDIT_TEXT_LENGTH - short.length - "\n\n".length - wrapperOverhead).coerceAtLeast(0)
        val detailedEscaped = escapeAndTrim(detailedRaw, detailedBudget)
        return "$short\n\n$BLOCKQUOTE_OPEN$detailedEscaped$BLOCKQUOTE_CLOSE"
    }

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
        private const val MAX_EDIT_TEXT_LENGTH = 4096
        private const val BLOCKQUOTE_OPEN = "<blockquote expandable>"
        private const val BLOCKQUOTE_CLOSE = "</blockquote>"
        private const val KEY_PLACEHOLDER_SHORT = "ai.description.placeholder.short"
        private const val KEY_PLACEHOLDER_DETAILED = "ai.description.placeholder.detailed"
        private const val KEY_FALLBACK = "ai.description.fallback.unavailable"
    }
}
