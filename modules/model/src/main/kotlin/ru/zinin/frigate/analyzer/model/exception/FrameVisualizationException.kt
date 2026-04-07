package ru.zinin.frigate.analyzer.model.exception

/**
 * Exception thrown when frame visualization fails
 */
class FrameVisualizationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
