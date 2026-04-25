package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

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

        val short = node["short"]?.asText().orEmpty()
        val detailed = node["detailed"]?.asText().orEmpty()

        if (short.isBlank()) {
            throw DescriptionException.InvalidResponse(
                IllegalStateException("missing or blank 'short' field"),
            )
        }
        if (detailed.isBlank()) {
            throw DescriptionException.InvalidResponse(
                IllegalStateException("missing or blank 'detailed' field"),
            )
        }

        return DescriptionResult(
            short = truncate(short, shortMaxLength),
            detailed = truncate(detailed, detailedMaxLength),
        )
    }

    private fun extractJsonBlock(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start in 0 until end) trimmed.substring(start, end + 1) else trimmed
    }

    private fun truncate(
        text: String,
        maxLength: Int,
    ): String {
        if (text.length <= maxLength) return text
        // Avoid splitting a UTF-16 surrogate pair — substring(…, maxLength-1) could land
        // between a high- and low-surrogate char (astral-plane codepoints like emoji, rare CJK).
        val rawCut = maxLength - 1
        val cut = if (rawCut > 0 && text[rawCut - 1].isHighSurrogate()) rawCut - 1 else rawCut
        return text.substring(0, cut) + "…"
    }
}
