package ru.zinin.frigate.analyzer.service.impl

import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.NewNotificationVerdict
import ru.zinin.frigate.analyzer.model.dto.VerdictCountRow
import ru.zinin.frigate.analyzer.model.persistent.NotificationVerdictEntity
import ru.zinin.frigate.analyzer.service.NotificationVerdictService
import ru.zinin.frigate.analyzer.service.repository.JudgeStatsRepository
import ru.zinin.frigate.analyzer.service.repository.NotificationVerdictRepository
import java.time.Clock
import java.time.Instant

@Service
class NotificationVerdictServiceImpl(
    private val repository: NotificationVerdictRepository,
    private val stats: JudgeStatsRepository,
    private val uuid: UUIDGeneratorHelper,
    private val clock: Clock,
) : NotificationVerdictService {
    override suspend fun record(verdict: NewNotificationVerdict): NotificationVerdictEntity {
        val entity =
            NotificationVerdictEntity(
                id = uuid.generateV1(),
                createdAt = Instant.now(clock),
                recordingId = verdict.recordingId,
                camId = verdict.camId,
                recordTimestamp = verdict.recordTimestamp,
                stage = verdict.stage.name,
                verdict = verdict.verdict.name,
                reason = verdict.reason.name,
                trackerReason = verdict.trackerReason,
                classes = verdict.classes,
                confidence = verdict.confidence?.toFloat(),
                summary = verdict.summary,
                wanted = verdict.wanted,
                snoozeUntil = verdict.snoozeUntil,
                presetId = verdict.presetId,
                model = verdict.model,
                latencyMs = verdict.latencyMs,
                contextJson = verdict.contextJson,
                error = verdict.error,
            )
        return repository.save(entity)
    }

    override suspend fun recentForCamera(
        camId: String,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<NotificationVerdictEntity> = repository.findRecentForCamera(camId, from, to, limit)

    override suspend fun lastPublished(camId: String): NotificationVerdictEntity? = repository.findLastPublished(camId)

    override suspend fun latest(
        camId: String?,
        limit: Int,
    ): List<NotificationVerdictEntity> =
        if (camId == null) {
            repository.findLatest(limit)
        } else {
            repository.findLatestByCamera(camId, limit)
        }

    override suspend fun countersSince(since: Instant): List<VerdictCountRow> = stats.verdictCounters(since)
}
