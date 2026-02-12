package ru.zinin.frigate.analyzer.model.exception

class DetectServerUnavailableException(
    message: String = "No detect server available",
) : Exception(message)
