package ru.zinin.frigate.analyzer.core.pipeline.frame

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.facade.RecordingProcessingFacade
import ru.zinin.frigate.analyzer.core.service.DetectService
import ru.zinin.frigate.analyzer.core.service.DetectionPostProcessor
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
class FrameAnalyzerConsumer(
    private val detectService: DetectService,
    private val recordingTracker: RecordingTracker,
    private val recordingProcessingFacade: RecordingProcessingFacade,
    private val detectionPostProcessor: DetectionPostProcessor,
) {
    /**
     * Основной цикл консьюмера. Получает задачи из канала и обрабатывает их.
     */
    suspend fun consume(
        channel: ReceiveChannel<FrameTask>,
        consumerId: Int,
    ) {
        logger.info { "Frame analyzer consumer #$consumerId started" }

        for (task in channel) {
            processTaskSafely(task, consumerId)
        }

        logger.info { "Consumer #$consumerId finished (channel closed)" }
    }

    private suspend fun processTaskSafely(
        task: FrameTask,
        consumerId: Int,
    ) {
        var shouldFinalize: Boolean

        try {
            shouldFinalize = processFrame(task, consumerId)
        } catch (e: CancellationException) {
            logger.info { "Consumer #$consumerId cancelled" }
            throw e
        } catch (e: DetectTimeoutException) {
            logger.error { "Consumer #$consumerId: Detection timeout for frame ${task.frameIndex}" }
            shouldFinalize = recordingTracker.markFailed(task.recordId, task.frameIndex)
        } catch (e: Exception) {
            logger.error(e) { "Consumer #$consumerId: Error processing frame ${task.frameIndex}" }
            shouldFinalize = recordingTracker.markFailed(task.recordId, task.frameIndex)
        }

        if (shouldFinalize) {
            finalizeRecording(task.recordId)
        }
    }

    /**
     * Обрабатывает кадр и возвращает флаг необходимости финализации.
     */
    private suspend fun processFrame(
        task: FrameTask,
        consumerId: Int,
    ): Boolean {
        logger.debug { "Consumer #$consumerId processing frame ${task.frameIndex}" }

        // Выполняем детекцию (retry встроен)
        val initialResponse = detectService.detectWithRetry(task.frameBytes)

        logger.debug {
            "Consumer #$consumerId: Detected ${initialResponse.detections.size} objects on model ${initialResponse.model}"
        }

        val finalResponse =
            detectionPostProcessor.process(
                bytes = task.frameBytes,
                initialResponse = initialResponse,
                frameIndex = task.frameIndex,
                consumerId = consumerId,
            )

        logger.debug {
            "Consumer #$consumerId: Detected ${finalResponse.detections.size} objects (final)"
        }

        // Отмечаем успешную обработку
        return recordingTracker.markCompleted(task.recordId, task.frameIndex, finalResponse)
    }

    private suspend fun finalizeRecording(recordId: UUID) {
        logger.info { "Finalizing recording $recordId" }

        val state = recordingTracker.removeRecording(recordId)
        if (state == null) {
            logger.warn { "Recording $recordId not found in tracker during finalization" }
            return
        }

        try {
            val frames = state.getFrames()
            val failedCount = state.getFailedCount()

            recordingProcessingFacade.processAndNotify(
                SaveProcessingResultRequest(recordId, frames),
                failedFramesCount = failedCount,
            )

            logger.info {
                "Recording $recordId completed: ${state.getCompletedCount()} successful, " +
                    "$failedCount failed out of ${state.totalFrames} frames"
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to finalize recording $recordId" }
        }
    }
}
