package ru.zinin.frigate.analyzer.model.response

data class StatisticsResponse(
    val recordings: RecordingsStatistics,
    val detectServers: List<DetectServerStatistics>,
)
