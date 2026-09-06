package ru.zinin.frigate.analyzer.core.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.ai.description.api.ActiveJudgePreset
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings
import ru.zinin.frigate.analyzer.core.judge.NotificationJudgeService
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.task.CameraSignalState
import ru.zinin.frigate.analyzer.core.task.SignalLossMonitorTask
import ru.zinin.frigate.analyzer.model.dto.CameraState
import ru.zinin.frigate.analyzer.model.dto.CameraStatusDto
import ru.zinin.frigate.analyzer.model.dto.VerdictDecision
import ru.zinin.frigate.analyzer.model.dto.VerdictStage
import ru.zinin.frigate.analyzer.model.response.CameraSnoozeDto
import ru.zinin.frigate.analyzer.model.response.CameraStatistics
import ru.zinin.frigate.analyzer.model.response.CamerasSection
import ru.zinin.frigate.analyzer.model.response.JudgeCounters
import ru.zinin.frigate.analyzer.model.response.JudgeSection
import ru.zinin.frigate.analyzer.model.response.RecordingsStatistics
import ru.zinin.frigate.analyzer.model.response.ServerStatus
import ru.zinin.frigate.analyzer.model.response.StatusResponse
import ru.zinin.frigate.analyzer.service.NotificationVerdictService
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class StatusService(
    private val recordingRepository: RecordingEntityRepository,
    private val detectServerLoadBalancer: DetectServerLoadBalancer,
    // Absent when application.signal-loss.enabled=false — see SignalLossMonitorTask @ConditionalOnProperty.
    private val signalLossMonitorTask: ObjectProvider<SignalLossMonitorTask>,
    private val clock: Clock,
    // Absent when application.ai.judge.enabled=false — see NotificationJudgeService @ConditionalOnProperty.
    private val judgeService: ObjectProvider<NotificationJudgeService>,
    private val verdictService: NotificationVerdictService,
    private val judgeRuntimeSettings: ObjectProvider<JudgeRuntimeSettings>,
    private val activeJudgePreset: ObjectProvider<ActiveJudgePreset>,
) {
    suspend fun collect(): StatusResponse {
        val now = Instant.now(clock)
        val recordings = buildRecordings()
        val cameras = buildCameras(now)
        val servers =
            detectServerLoadBalancer
                .getAllServersStatistics()
                .sortedWith(compareBy({ if (it.status == ServerStatus.DEAD) 0 else 1 }, { it.id }))
        return StatusResponse(
            recordings = recordings,
            cameras = cameras,
            detectServers = servers,
            judge = buildJudge(now),
        )
    }

    private suspend fun buildJudge(now: Instant): JudgeSection {
        val judge = judgeService.ifAvailable ?: return JudgeSection.disabled()
        val rows = verdictService.countersSince(now.minus(Duration.ofHours(24)))
        val published = rows.filter { it.verdict == VerdictDecision.PUBLISH.name }.sumOf { it.count }
        val suppressed =
            rows
                .filter { it.stage == VerdictStage.JUDGE.name && it.verdict == VerdictDecision.SUPPRESS.name }
                .groupBy { it.reason }
                .mapValues { (_, grouped) -> grouped.sumOf { it.count } }
        val failover = rows.filter { it.stage == VerdictStage.FAILOVER.name }.sumOf { it.count }
        val snoozed = rows.filter { it.stage == VerdictStage.SNOOZE.name }.sumOf { it.count }
        return JudgeSection(
            enabled = true,
            runtimeEnabled = failSoft(true) { judgeRuntimeSettings.ifAvailable?.judgeEnabled() ?: true },
            presetId = failSoft(null) { activeJudgePreset.ifAvailable?.effective()?.id },
            last24h = JudgeCounters(published, suppressed, failover, snoozed),
            snoozes =
                judge.snapshotSnoozes().map { snooze ->
                    CameraSnoozeDto(
                        camId = snooze.camId,
                        until = snooze.until,
                        classes =
                            snooze.covered.entries
                                .sortedBy { it.key }
                                .joinToString(",") { "${it.key}:${it.value}" },
                    )
                },
        )
    }

    private suspend fun <T> failSoft(
        fallback: T,
        read: suspend () -> T,
    ): T =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Judge status read failed; using fallback" }
            fallback
        }

    private suspend fun buildRecordings(): RecordingsStatistics {
        val counts = recordingRepository.getRecordingCounts()
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
            total = counts.total,
            processed = counts.processed,
            unprocessed = counts.unprocessed,
            success = counts.success,
            errors = counts.errors,
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
