package ru.zinin.frigate.analyzer.ai.description.api

sealed class DescriptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class Timeout(
        cause: Throwable? = null,
    ) : DescriptionException("Description timed out", cause)

    class InvalidResponse(
        cause: Throwable? = null,
    ) : DescriptionException("Claude returned invalid JSON", cause)

    class Transport(
        cause: Throwable? = null,
    ) : DescriptionException("Claude transport error", cause)

    class RateLimited(
        cause: Throwable? = null,
    ) : DescriptionException("Claude rate-limited (429)", cause)
}
