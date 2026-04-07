package ru.zinin.frigate.analyzer.model.exception

/**
 * Exception thrown when a detection timeout is exceeded
 */
class DetectTimeoutException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
