package ru.zinin.frigate.analyzer.core.facade

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.service.FrameVisualizationService
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService

private val logger = KotlinLogging.logger {}

@Component
class RecordingProcessingFacade(
    private val recordingEntityService: RecordingEntityService,
    private val telegramNotificationService: TelegramNotificationService,
    private val frameVisualizationService: FrameVisualizationService,
) {
    suspend fun processAndNotify(
        request: SaveProcessingResultRequest,
        failedFramesCount: Int = 0,
    ) {
        val recordingId = request.recordingId

        if (failedFramesCount > 0) {
            logger.warn {
                "Recording $recordingId has $failedFramesCount failed frames, " +
                    "skipping save (will retry automatically)"
            }
            return
        }

        // Визуализируем кадры ДО сохранения результата.
        // Если визуализация не удастся, запись будет повторно обработана.
        val visualizedFrames = frameVisualizationService.visualizeFrames(request.frames)

        try {
            // Save processing result within @Transactional
            recordingEntityService.saveProcessingResult(request)

            // Fetch recording DTO for notification
            val recording = recordingEntityService.getRecording(recordingId)
            if (recording != null) {
                try {
                    telegramNotificationService.sendRecordingNotification(recording, visualizedFrames)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to send telegram notification for recording $recordingId" }
                    // Do not rethrow - notification failure should not affect processing
                }
            } else {
                logger.warn { "Recording $recordingId not found after saving, skipping notification" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save processing result for recording $recordingId" }
            throw e // Rethrow to let caller handle the error
        }
    }
}
