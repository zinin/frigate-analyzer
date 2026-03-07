package ru.zinin.frigate.analyzer.model.exception

class UnprocessableVideoException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
