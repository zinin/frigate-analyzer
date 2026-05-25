package ru.zinin.frigate.analyzer.model.response

import ru.zinin.frigate.analyzer.model.dto.CameraStatusDto

data class StatusResponse(
    val recordings: RecordingsStatistics,
    val cameras: CamerasSection,
    val detectServers: List<DetectServerStatistics>,
)

data class CamerasSection(
    val monitoringEnabled: Boolean,
    val items: List<CameraStatusDto>,
)
