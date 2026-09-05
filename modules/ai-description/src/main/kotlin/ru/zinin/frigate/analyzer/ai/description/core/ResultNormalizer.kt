package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

/**
 * Провайдер-нейтральная нормализация ответа модели: непустые поля и обрезка по лимитам.
 * [DescriptionResponseParser] извлекает `short` и `detailed` из сырого текста и отдаёт их сюда.
 */
object ResultNormalizer {
    fun normalize(
        short: String?,
        detailed: String?,
        shortMaxLength: Int,
        detailedMaxLength: Int,
    ): DescriptionResult {
        if (short.isNullOrBlank()) {
            throw DescriptionException.InvalidResponse(detail = "missing or blank 'short' field")
        }
        if (detailed.isNullOrBlank()) {
            throw DescriptionException.InvalidResponse(detail = "missing or blank 'detailed' field")
        }
        return DescriptionResult(
            short = truncate(short, shortMaxLength),
            detailed = truncate(detailed, detailedMaxLength),
        )
    }

    internal fun truncate(
        text: String,
        maxLength: Int,
    ): String {
        if (text.length <= maxLength) return text
        // Не рвём UTF-16 суррогатную пару: substring(…, maxLength-1) может попасть между
        // high- и low-surrogate (эмодзи, редкие CJK).
        val rawCut = maxLength - 1
        val cut = if (rawCut > 0 && text[rawCut - 1].isHighSurrogate()) rawCut - 1 else rawCut
        return text.substring(0, cut) + "…"
    }
}
