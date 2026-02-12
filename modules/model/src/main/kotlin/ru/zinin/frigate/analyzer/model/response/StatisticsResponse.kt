package ru.zinin.frigate.analyzer.model.response

data class StatisticsResponse(
    val recordings: RecordingsStatistics,
    val detectServers: List<DetectServerStatistics>,
)

data class RecordingsStatistics(
    val total: Long,
    val processed: Long,
    val unprocessed: Long,
    val byCameras: List<CameraStatistics>,
    val processingRatePerMinute: Double,
)

data class CameraStatistics(
    val camId: String,
    val recordingsCount: Long,
    val recordingsProcessed: Long,
    val detectionsCount: Long,
)

data class DetectServerStatistics(
    val id: String,
    val status: ServerStatus,
    val frameRequests: ServerLoad,
    val frameExtractionRequests: ServerLoad,
    val visualizeRequests: ServerLoad,
)

data class ServerLoad(
    val current: Int,
    val maximum: Int,
)

enum class ServerStatus {
    ALIVE,
    DEAD,
}
