package ru.zinin.frigate.analyzer.core.service

import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.task.CameraSignalState
import ru.zinin.frigate.analyzer.core.task.SignalLossMonitorTask
import ru.zinin.frigate.analyzer.model.dto.CameraState
import ru.zinin.frigate.analyzer.model.dto.CameraStatusDto
import ru.zinin.frigate.analyzer.model.response.CameraStatistics
import ru.zinin.frigate.analyzer.model.response.CamerasSection
import ru.zinin.frigate.analyzer.model.response.RecordingsStatistics
import ru.zinin.frigate.analyzer.model.response.ServerStatus
import ru.zinin.frigate.analyzer.model.response.StatusResponse
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class StatusService(
    private val recordingRepository: RecordingEntityRepository,
    private val detectServerLoadBalancer: DetectServerLoadBalancer,
    // Absent when application.signal-loss.enabled=false — see SignalLossMonitorTask @ConditionalOnProperty.
    private val signalLossMonitorTask: ObjectProvider<SignalLossMonitorTask>,
    private val clock: Clock,
) {
    suspend fun collect(): StatusResponse {
        val recordings = buildRecordings()
        val cameras = buildCameras(Instant.now(clock))
        val servers =
            detectServerLoadBalancer
                .getAllServersStatistics()
                .sortedWith(compareBy({ if (it.status == ServerStatus.DEAD) 0 else 1 }, { it.id }))
        return StatusResponse(
            recordings = recordings,
            cameras = cameras,
            detectServers = servers,
        )
    }

    private suspend fun buildRecordings(): RecordingsStatistics {
        val total = recordingRepository.countAll()
        val processed = recordingRepository.countProcessed()
        val unprocessed = recordingRepository.countUnprocessed()
        // Two near-identical types with the same positional fields: `CameraStatisticsDto`
        // (`model.dto`, SQL projection from RecordingEntityRepository) → `CameraStatistics`
        // (`model.response`, JSON contract). Mapping is mandatory to avoid leaking the
        // SQL/R2DBC layer into the response. The SQL query already orders by `cam_id ASC`
        // — relying on that invariant to keep the `byCameras` list stable.
        val byCameras =
            recordingRepository.getStatisticsByCameras().map { dto ->
                CameraStatistics(
                    camId = dto.camId,
                    recordingsCount = dto.recordingsCount,
                    recordingsProcessed = dto.recordingsProcessed,
                    detectionsCount = dto.detectionsCount,
                )
            }
        val rate = recordingRepository.getProcessingRatePerMinuteLast5Minutes()
        return RecordingsStatistics(
            total = total,
            processed = processed,
            unprocessed = unprocessed,
            byCameras = byCameras,
            processingRatePerMinute = rate,
        )
    }

    private fun buildCameras(now: Instant): CamerasSection {
        val monitor = signalLossMonitorTask.ifAvailable
        if (monitor == null) {
            return CamerasSection(monitoringEnabled = false, items = emptyList())
        }
        val items =
            monitor
                .snapshotStates()
                .map { (camId, state) -> toDto(camId, state, now) }
                .sortedWith(compareBy({ if (it.state == CameraState.OFFLINE) 0 else 1 }, { it.camId }))
        return CamerasSection(monitoringEnabled = true, items = items)
    }

    private fun toDto(
        camId: String,
        state: CameraSignalState,
        now: Instant,
    ): CameraStatusDto =
        when (state) {
            is CameraSignalState.Healthy -> {
                CameraStatusDto(
                    camId = camId,
                    state = CameraState.HEALTHY,
                    lastSeenAt = state.lastSeenAt,
                    offlineFor = null,
                )
            }

            is CameraSignalState.SignalLost -> {
                CameraStatusDto(
                    camId = camId,
                    state = CameraState.OFFLINE,
                    lastSeenAt = state.lastSeenAt,
                    offlineFor = Duration.between(state.lastSeenAt, now).coerceAtLeast(Duration.ZERO),
                )
            }
        }
}
