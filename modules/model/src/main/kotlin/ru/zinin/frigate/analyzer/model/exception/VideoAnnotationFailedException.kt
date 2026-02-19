package ru.zinin.frigate.analyzer.model.exception

class VideoAnnotationFailedException(
    message: String?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
