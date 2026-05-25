package ru.zinin.frigate.analyzer.model.response

data class DetectServerStatistics(
    val id: String,
    val status: ServerStatus,
    val frameRequests: ServerLoad,
    val frameExtractionRequests: ServerLoad,
    val visualizeRequests: ServerLoad,
    val videoVisualizeRequests: ServerLoad,
)

data class ServerLoad(
    val current: Int,
    val maximum: Int,
)

enum class ServerStatus {
    ALIVE,
    DEAD,
}
