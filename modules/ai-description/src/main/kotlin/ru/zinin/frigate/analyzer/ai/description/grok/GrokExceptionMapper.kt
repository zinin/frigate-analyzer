package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException

/**
 * Классификация неудачного запуска `grok` по spec: авторизация раньше rate limit, rate limit
 * раньше общего Transport. Unauthorized и RateLimited агент не повторяет, Transport повторяет
 * один раз.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokExceptionMapper {
    fun fromFailure(
        exitCode: Int,
        errorMessage: String?,
        stderrTail: String,
    ): DescriptionException {
        val message = errorMessage ?: stderrTail.trim().ifBlank { "grok exited with code $exitCode" }
        val lower = message.lowercase()
        return when {
            isUnauthorized(lower) -> {
                DescriptionException.Unauthorized(message)
            }

            isRateLimited(lower) -> {
                DescriptionException.RateLimited(detail = message)
            }

            else -> {
                DescriptionException.Transport(detail = "exit $exitCode: $message")
            }
        }
    }

    /**
     * Эндпоинт модели не принимает `--json-schema`. Проверено на BYOK-моделях: LiteLLM-гейт отвечает
     * `failed to parse grammar`, DeepSeek — `This response_format type is unavailable now`. Такой
     * запуск повторяется без схемы, а ответ читается из текстового JSON.
     */
    fun isStructuredOutputUnsupported(message: String): Boolean {
        val lower = message.lowercase()
        return SCHEMA_MARKERS.any { it in lower }
    }

    /** exit 0, но structured output неполный: решает stopReason. */
    fun fromStopReason(stopReason: String?): DescriptionException =
        when (stopReason) {
            "cancelled" -> DescriptionException.Transport(detail = "grok reported stopReason=cancelled")
            else -> DescriptionException.InvalidResponse(detail = "no structured output, stopReason=${stopReason ?: "unknown"}")
        }

    private fun isUnauthorized(lower: String): Boolean {
        if (AUTH_MARKERS.any { it in lower }) return true
        if ("refresh token" in lower && REFRESH_TOKEN_CONTEXT.any { it in lower }) return true
        return AUTH_401.containsMatchIn(lower) &&
            ("http" in lower || "status" in lower || "api" in lower)
    }

    private fun isRateLimited(lower: String): Boolean {
        if (RATE_LIMIT_MARKERS.any { it in lower }) return true
        return RATE_LIMIT_429.containsMatchIn(lower) &&
            ("http" in lower || "status" in lower || "api" in lower)
    }

    companion object {
        val AUTH_MARKERS =
            listOf(
                "not signed in",
                "grok login",
                "not authenticated",
                "unauthorized",
                "invalid_grant",
                "authentication failed",
                "invalid api key",
                "invalid_api_key",
            )
        val REFRESH_TOKEN_CONTEXT = listOf("invalid", "expired", "rejected", "failed", "revoked")
        val RATE_LIMIT_MARKERS = listOf("rate limit", "too many requests")
        val SCHEMA_MARKERS =
            listOf(
                "response_format",
                "response format",
                "json_schema",
                "json schema",
                "--json-schema",
                "structured output",
                "structured_output",
                "parse grammar",
                "invalid grammar",
            )
        val RATE_LIMIT_429 = Regex("\\b429\\b")
        val AUTH_401 = Regex("\\b401\\b")
    }
}
