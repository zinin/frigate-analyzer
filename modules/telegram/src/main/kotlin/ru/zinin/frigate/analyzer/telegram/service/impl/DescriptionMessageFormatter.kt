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
        // Cap `short` first so it can never consume the entire caption budget. Without this,
        // a dense HTML-special `result.short` could inflate up to 5× after escape, `suffix.length`
        // could exceed `maxLength`, `escapeAndTrim(baseText, negative)` would return "", and the
        // final caption would be just `suffix` — itself longer than 1024 → Telegram rejects the edit.
        // Reserve a minimum slot for baseText so there's always *something* identifying the recording.
        val shortBudget = (maxLength - BASE_TEXT_MIN_RESERVE - "\n\n".length).coerceAtLeast(0)
        val suffix = "\n\n${escapeAndTrim(result.short, shortBudget)}"
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
    ): String {
        // Bound to Telegram's 4096-char `editMessageText` limit. Even with detailedMaxLength=3500
        // (@Max) an HTML-dense detailed (& < >) can exceed 4096 after escape; mediaGroupText has
        // the same guard — keep single-photo path consistent.
        val budget = (MAX_EDIT_TEXT_LENGTH - BLOCKQUOTE_OPEN.length - BLOCKQUOTE_CLOSE.length).coerceAtLeast(0)
        val detailed = escapeAndTrim(result.detailed, budget)
        return "$BLOCKQUOTE_OPEN$detailed$BLOCKQUOTE_CLOSE"
    }

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
        // Avoid splitting a UTF-16 surrogate pair (astral-plane chars like emoji or rare CJK).
        if (cutoff > 0 && escaped[cutoff - 1].isHighSurrogate()) {
            cutoff -= 1
        }
        return escaped.substring(0, cutoff.coerceAtLeast(0)) + "…"
    }

    companion object {
        private const val MAX_CAPTION_LENGTH = 1024
        private const val MAX_EDIT_TEXT_LENGTH = 4096
        private const val BLOCKQUOTE_OPEN = "<blockquote expandable>"
        private const val BLOCKQUOTE_CLOSE = "</blockquote>"

        // Minimum characters reserved for baseText (camId/filePath) when capping `short` in
        // `captionSuccess`. A tiny identifying fragment is better than a caption that would
        // overflow the 1024 cap and fail the Telegram edit.
        private const val BASE_TEXT_MIN_RESERVE = 64

        private const val KEY_PLACEHOLDER_SHORT = "ai.description.placeholder.short"
        private const val KEY_PLACEHOLDER_DETAILED = "ai.description.placeholder.detailed"
        private const val KEY_FALLBACK = "ai.description.fallback.unavailable"
    }
}
