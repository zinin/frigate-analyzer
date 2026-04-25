package ru.zinin.frigate.analyzer.telegram.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Deferred
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class NoOpTelegramNotificationService : TelegramNotificationService {
    override suspend fun sendRecordingNotification(
        recording: RecordingDto,
        visualizedFrames: List<VisualizedFrameData>,
        descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>?)?,
    ) {
        logger.debug { "Telegram notifications disabled, skipping notification for recording ${recording.id}" }
    }

    override suspend fun sendCameraSignalLost(
        camId: String,
        lastSeenAt: Instant,
        now: Instant,
    ) {
        logger.debug { "Telegram notifications disabled, skipping signal-loss for camera $camId" }
    }

    override suspend fun sendCameraSignalRecovered(
        camId: String,
        downtime: Duration,
    ) {
        logger.debug { "Telegram notifications disabled, skipping signal-recovery for camera $camId" }
    }
}
