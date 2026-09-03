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
            AUTH_MARKERS.any { it in lower } -> {
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

    /** exit 0, но structured output неполный: решает stopReason. */
    fun fromStopReason(stopReason: String?): DescriptionException =
        when (stopReason) {
            "cancelled" -> DescriptionException.Transport(detail = "grok reported stopReason=cancelled")
            else -> DescriptionException.InvalidResponse(detail = "no structured output, stopReason=${stopReason ?: "unknown"}")
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
                "refresh token",
                "authentication failed",
            )
        val RATE_LIMIT_MARKERS = listOf("rate limit", "too many requests")
        val RATE_LIMIT_429 = Regex("\\b429\\b")
    }
}
