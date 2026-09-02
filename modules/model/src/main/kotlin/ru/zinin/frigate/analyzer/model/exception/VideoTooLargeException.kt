package ru.zinin.frigate.analyzer.model.exception

/**
 * The exported video does not fit into the Telegram upload limit, even after re-encoding.
 */
class VideoTooLargeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
