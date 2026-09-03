package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Разобранный stdout `grok --output-format json`. Поля structured output могут отсутствовать. */
data class GrokOutput(
    val stopReason: String?,
    val sessionId: String?,
    val short: String?,
    val detailed: String?,
    /** Одна строка для DEBUG-лога: токены и стоимость. */
    val usageSummary: String,
)

/**
 * `--output-format json` даёт один объект: `text`, `stopReason`, `sessionId`, `usage`,
 * `modelUsage`, `total_cost_usd` и `structuredOutput` (объект по `--json-schema`). При ошибке
 * на stdout лежит `{"type":"error","message":"…"}`.
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
        return GrokOutput(
            stopReason = node["stopReason"]?.textOrNull(),
            sessionId = node["sessionId"]?.textOrNull(),
            short = structured?.get("short")?.textOrNull(),
            detailed = structured?.get("detailed")?.textOrNull(),
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

    private fun JsonNode.textOrNull(): String? = if (isNull) null else asText()

    private fun usageSummary(node: JsonNode): String {
        val usage = node["usage"] ?: return "usage=absent"
        val cost = node["total_cost_usd"]?.textOrNull() ?: "unknown"
        return listOf("input_tokens", "cache_read_input_tokens", "output_tokens", "reasoning_tokens")
            .joinToString(" ") { key -> "$key=${usage[key]?.textOrNull() ?: "?"}" } +
            " total_cost_usd=$cost"
    }
}
