package ru.zinin.frigate.analyzer.ai.description.api

/**
 * Ошибки провайдера описаний, общие для всех провайдеров. Тексты нейтральны: провайдер и
 * подробности живут в `detail`, а не в типе.
 */
sealed class DescriptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class Timeout(
        cause: Throwable? = null,
    ) : DescriptionException("Description timed out", cause)

    class InvalidResponse(
        cause: Throwable? = null,
        detail: String? = null,
    ) : DescriptionException(withDetail("Description provider returned an invalid response", detail), cause)

    class Transport(
        cause: Throwable? = null,
        detail: String? = null,
    ) : DescriptionException(withDetail("Description provider transport error", detail), cause)

    class RateLimited(
        cause: Throwable? = null,
        detail: String? = null,
    ) : DescriptionException(withDetail("Description provider rate-limited the request", detail), cause)

    /**
     * Провайдер отверг учётные данные. Не повторяется агентом; на первом таком отказе ядро
     * публикует [DescriptionProviderAuthEvent] со state = LOST.
     */
    class Unauthorized(
        val detail: String,
        cause: Throwable? = null,
    ) : DescriptionException("Description provider rejected the credentials: $detail", cause)
}

private fun withDetail(
    base: String,
    detail: String?,
): String = if (detail.isNullOrBlank()) base else "$base: $detail"
