package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Разобранный stdout `grok --output-format json`. Оба представления ответа могут отсутствовать. */
data class GrokOutput(
    val stopReason: String?,
    val sessionId: String?,
    /** Объект `structuredOutput` целиком, если эндпоинт применил `--json-schema`. */
    val structured: String?,
    /** Текст ответа; у моделей, игнорирующих схему, тот же объект лежит здесь, иногда в фенсе. */
    val text: String?,
    val usageSummary: String,
) {
    /** Что задача разбирает первым: объект по схеме авторитетнее свободного текста. */
    val payload: String? get() = structured ?: text

    /**
     * Запасной разбор той же попытки. Структура бывает неполной там, где текст уже несёт готовый
     * объект (модель ответила, но схему применила частично), и держаться за неё значило бы
     * выбросить оплаченный ответ и заплатить за повтор.
     */
    val fallback: String? get() = text?.takeIf { structured != null && it != structured }

    val fromText: Boolean get() = structured == null && text != null
}

/**
 * `--output-format json` даёт один объект: `text`, `stopReason`, `sessionId`, `usage`,
 * `modelUsage`, `total_cost_usd` и `structuredOutput` (объект по `--json-schema`). При ошибке
 * на stdout лежит `{"type":"error","message":"…"}`.
 *
 * `structuredOutput` заполняют только эндпоинты, которые применяют `--json-schema`. BYOK-модели
 * из `config.toml` схему часто игнорируют и кладут тот же объект в `text`, иногда в markdown-фенс,
 * поэтому парсер отдаёт оба представления: [GrokOutput.payload] задача разбирает первым,
 * [GrokOutput.fallback] — если первое она отвергла. Сам разбор JSON — дело задачи.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class GrokOutputParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(stdout: String): GrokOutput {
        val node =
            readObject(stdout)
                ?: throw DescriptionException.InvalidResponse(detail = "stdout is not a JSON object: ${stdout.take(200)}")
        val structured = node["structuredOutput"]?.takeIf { it.isObject }
        val text = node["text"]?.textOrNull()?.takeUnless { it.isBlank() }
        return GrokOutput(
            stopReason = node["stopReason"]?.textOrNull(),
            sessionId = node["sessionId"]?.textOrNull(),
            structured = structured?.let { objectMapper.writeValueAsString(it) },
            text = text,
            usageSummary = usageSummary(node),
        )
    }

    /** Текст из error-конверта или null, если stdout не такой конверт. */
    fun errorMessage(stdout: String): String? {
        val node = readObject(stdout) ?: return null
        if (node["type"]?.textOrNull() != "error") return null
        return node["message"]?.textOrNull()
    }

    private fun readObject(text: String): JsonNode? =
        try {
            objectMapper.readTree(text).takeIf { it.isObject }
        } catch (e: JacksonException) {
            null
        }

    private fun JsonNode.textOrNull(): String? = if (isString) stringValue() else null

    /**
     * Значение скалярного поля для строки лога. Проверка на `isValueNode` обязательна: `asString()`
     * в Jackson 3 бросает на объектах и массивах, а [usageSummary] считается на успешном пути, до
     * возврата [GrokOutput], — строка DEBUG-лога не должна стоить уже оплаченного ответа модели.
     */
    private fun JsonNode.anyAsText(): String? = if (isValueNode && !isNull) asString() else null

    private fun usageSummary(node: JsonNode): String {
        val usage = node["usage"] ?: return "usage=absent"
        val cost = node["total_cost_usd"]?.anyAsText() ?: "unknown"
        return listOf("input_tokens", "cache_read_input_tokens", "output_tokens", "reasoning_tokens")
            .joinToString(" ") { key -> "$key=${usage[key]?.anyAsText() ?: "?"}" } +
            " total_cost_usd=$cost"
    }
}
