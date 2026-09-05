package ru.zinin.frigate.analyzer.ai.description.core

/**
 * Вырезает JSON-объект из свободного текста модели: снимает обрамляющую прозу и markdown-фенс
 * ```json … ```. Нужен обоим провайдерам: Claude отвечает текстом всегда, а BYOK-модели Grok
 * игнорируют `--json-schema` и кладут тот же объект в поле `text`.
 */
object JsonBlockExtractor {
    fun extract(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start in 0 until end) trimmed.substring(start, end + 1) else trimmed
    }
}
