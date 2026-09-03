package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.core.JsonBlockExtractor
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Разобранный stdout `grok --output-format json`. Поля описания могут отсутствовать. */
data class GrokOutput(
    val stopReason: String?,
    val sessionId: String?,
    val short: String?,
    val detailed: String?,
    /** Одна строка для DEBUG-лога: токены и стоимость. */
    val usageSummary: String,
    /** `true`, когда поля пришли не из `structuredOutput`, а из JSON в тексте ответа. */
    val fromText: Boolean = false,
)

/**
 * `--output-format json` даёт один объект: `text`, `stopReason`, `sessionId`, `usage`,
 * `modelUsage`, `total_cost_usd` и `structuredOutput` (объект по `--json-schema`). При ошибке
 * на stdout лежит `{"type":"error","message":"…"}`.
 *
 * `structuredOutput` заполняют только эндпоинты, которые применяют `--json-schema`. BYOK-модели
 * из `config.toml` схему часто игнорируют и кладут тот же объект в `text`, иногда в markdown-фенс,
 * поэтому при пустом structured output поля читаются из текста.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokOutputParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(stdout: String): GrokOutput {
        val node =
            readObject(stdout)
                ?: throw DescriptionException.InvalidResponse(detail = "stdout is not a JSON object: ${stdout.take(200)}")
        val structured = node["structuredOutput"]?.takeIf { it.isObject }
        val short = structured?.get("short")?.textOrNull()
        val detailed = structured?.get("detailed")?.textOrNull()
        val fallback =
            if (short.isNullOrBlank() || detailed.isNullOrBlank()) {
                node["text"]?.textOrNull()?.let { readObject(JsonBlockExtractor.extract(it)) }
            } else {
                null
            }
        return GrokOutput(
            stopReason = node["stopReason"]?.textOrNull(),
            sessionId = node["sessionId"]?.textOrNull(),
            // takeUnless, а не Elvis: пустая строка в structuredOutput это тот же «поля нет»,
            // и держаться за неё значило бы выбросить готовый ответ из текста.
            short = short?.takeUnless { it.isBlank() } ?: fallback?.get("short")?.textOrNull(),
            detailed = detailed?.takeUnless { it.isBlank() } ?: fallback?.get("detailed")?.textOrNull(),
            usageSummary = usageSummary(node),
            fromText = fallback != null,
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

    private fun JsonNode.textOrNull(): String? = if (isTextual) asText() else null

    private fun JsonNode.anyAsText(): String? = if (isNull || isMissingNode) null else asText()

    private fun usageSummary(node: JsonNode): String {
        val usage = node["usage"] ?: return "usage=absent"
        val cost = node["total_cost_usd"]?.anyAsText() ?: "unknown"
        return listOf("input_tokens", "cache_read_input_tokens", "output_tokens", "reasoning_tokens")
            .joinToString(" ") { key -> "$key=${usage[key]?.anyAsText() ?: "?"}" } +
            " total_cost_usd=$cost"
    }
}
