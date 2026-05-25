package ru.zinin.frigate.analyzer.model.response

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
