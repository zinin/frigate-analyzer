package ru.zinin.frigate.analyzer.model.response

import ru.zinin.frigate.analyzer.model.dto.CameraStatusDto
import java.time.Instant

data class StatusResponse(
    val recordings: RecordingsStatistics,
    val cameras: CamerasSection,
    val detectServers: List<DetectServerStatistics>,
    val judge: JudgeSection = JudgeSection.disabled(),
)

data class CamerasSection(
    val monitoringEnabled: Boolean,
    val items: List<CameraStatusDto>,
)

data class JudgeSection(
    val enabled: Boolean,
    val runtimeEnabled: Boolean,
    val presetId: String?,
    val last24h: JudgeCounters,
    val snoozes: List<CameraSnoozeDto>,
) {
    companion object {
        fun disabled() =
            JudgeSection(
                enabled = false,
                runtimeEnabled = false,
                presetId = null,
                last24h = JudgeCounters(0, emptyMap(), 0, 0),
                snoozes = emptyList(),
            )
    }
}

data class JudgeCounters(
    val published: Long,
    val suppressedByReason: Map<String, Long>,
    val failover: Long,
    val snoozed: Long,
)

data class CameraSnoozeDto(
    val camId: String,
    val until: Instant,
    val classes: String,
)
