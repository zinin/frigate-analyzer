package ru.zinin.frigate.analyzer.model.exception

/**
 * Исключение при ошибке визуализации кадров
 */
class FrameVisualizationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
