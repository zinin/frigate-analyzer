package ru.zinin.frigate.analyzer.telegram.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService

private val logger = KotlinLogging.logger {}

@Service
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class NoOpTelegramNotificationService : TelegramNotificationService {
    override suspend fun sendRecordingNotification(
        recording: RecordingDto,
        visualizedFrames: List<VisualizedFrameData>,
    ) {
        logger.debug { "Telegram notifications disabled, skipping notification for recording ${recording.id}" }
    }
}
