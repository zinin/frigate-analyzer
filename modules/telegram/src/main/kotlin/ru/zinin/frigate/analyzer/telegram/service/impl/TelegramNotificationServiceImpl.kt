package ru.zinin.frigate.analyzer.telegram.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.telegram.queue.NotificationTask
import ru.zinin.frigate.analyzer.telegram.queue.TelegramNotificationQueue
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val logger = KotlinLogging.logger {}

@Service
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramNotificationServiceImpl(
    private val userService: TelegramUserService,
    private val notificationQueue: TelegramNotificationQueue,
    private val uuidGeneratorHelper: UUIDGeneratorHelper,
) : TelegramNotificationService {
    override suspend fun sendRecordingNotification(
        recording: RecordingDto,
        visualizedFrames: List<VisualizedFrameData>,
    ) {
        if (recording.detectionsCount == 0) {
            logger.debug { "No detections found, skipping notification for ${recording.filePath}" }
            return
        }

        val usersWithZones = userService.getAuthorizedUsersWithZones()
        if (usersWithZones.isEmpty()) {
            logger.debug { "No active subscribers found, skipping notification" }
            return
        }

        usersWithZones.forEach { (chatId, zone) ->
            val message = formatRecordingMessage(recording, zone)
            val task =
                NotificationTask(
                    uuidGeneratorHelper.generateV1(),
                    chatId,
                    message,
                    visualizedFrames,
                )
            notificationQueue.enqueue(task)
        }

        logger.debug { "Enqueued notification for ${usersWithZones.size} subscribers" }
    }

    private fun formatRecordingMessage(
        recording: RecordingDto,
        zone: ZoneId,
    ): String {
        val fileName = recording.filePath.substringAfterLast("/")
        val camId = recording.camId
        val detectionsCount = recording.detectionsCount
        val analyzedFrames = recording.analyzedFramesCount
        val analyzeTime = recording.analyzeTime

        val formatter =
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.LONG)
                .withLocale(java.util.Locale.of("ru"))

        val timestampFormatted =
            recording.processTimestamp
                ?.atZone(zone)
                ?.format(formatter)
                ?: "N/A"

        val recordTimestampFormatted =
            recording.recordTimestamp
                .atZone(zone)
                .format(formatter)

        return buildString {
            appendLine("📹 Обработка записи завершена")
            appendLine()
            appendLine("🎥 Камера: $camId")
            appendLine("📁 Файл: $fileName")
            appendLine("🔍 Обнаружений: $detectionsCount")
            appendLine("🖼 Кадров проанализировано: $analyzedFrames")
            appendLine("⏱ Время обработки: $analyzeTime сек")
            appendLine("📸 Запись: $recordTimestampFormatted")
            appendLine("⏰ Обработка: $timestampFormatted")
        }
    }
}
