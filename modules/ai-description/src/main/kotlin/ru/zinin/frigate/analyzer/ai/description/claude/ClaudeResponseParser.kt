package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.core.ResultNormalizer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeResponseParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(
        raw: String,
        shortMaxLength: Int,
        detailedMaxLength: Int,
    ): DescriptionResult {
        val jsonText = extractJsonBlock(raw)
        val node: JsonNode =
            try {
                objectMapper.readTree(jsonText)
            } catch (e: Exception) {
                logger.debug { "Claude response was not parseable as JSON: ${raw.take(200)}" }
                throw DescriptionException.InvalidResponse(e)
            }

        val short = node["short"]?.takeUnless { it.isNull }?.asText()
        val detailed = node["detailed"]?.takeUnless { it.isNull }?.asText()
        return ResultNormalizer.normalize(short, detailed, shortMaxLength, detailedMaxLength)
    }

    private fun extractJsonBlock(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start in 0 until end) trimmed.substring(start, end + 1) else trimmed
    }
}
