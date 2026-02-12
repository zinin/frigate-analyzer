package ru.zinin.frigate.analyzer.model.exception

/**
 * Исключение при превышении таймаута детекции
 */
class DetectTimeoutException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
