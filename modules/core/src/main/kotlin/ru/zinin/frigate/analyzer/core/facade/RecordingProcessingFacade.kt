package ru.zinin.frigate.analyzer.core.facade

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.core.config.DescriptionCoroutineScope
import ru.zinin.frigate.analyzer.core.service.FrameVisualizationService
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
class RecordingProcessingFacade(
    private val recordingEntityService: RecordingEntityService,
    private val telegramNotificationService: TelegramNotificationService,
    private val frameVisualizationService: FrameVisualizationService,
    private val descriptionAgentProvider: ObjectProvider<DescriptionAgent>,
    private val descriptionScope: DescriptionCoroutineScope,
    private val descriptionProperties: DescriptionProperties,
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

        // Visualize frames BEFORE saving the result.
        // If visualization fails, the recording will be reprocessed.
        val visualizedFrames = frameVisualizationService.visualizeFrames(request.frames)

        try {
            // Save processing result within @Transactional
            recordingEntityService.saveProcessingResult(request)

            // Fetch recording DTO for notification
            val recording = recordingEntityService.getRecording(recordingId)
            if (recording != null) {
                // Build supplier for lazy describe-job kick-off; invoked by Telegram layer
                // AFTER subscriber filtering so AI tokens are not wasted on zero-recipient recordings.
                val descriptionSupplier = buildDescriptionSupplier(recordingId, request)
                try {
                    telegramNotificationService.sendRecordingNotification(
                        recording,
                        visualizedFrames,
                        descriptionSupplier,
                    )
                } catch (e: CancellationException) {
                    throw e // structured concurrency — do not swallow cancellation
                } catch (e: Exception) {
                    logger.error(e) { "Failed to send telegram notification for recording $recordingId" }
                    // Do not rethrow - notification failure should not affect processing
                }
            } else {
                logger.warn { "Recording $recordingId not found after saving, skipping notification" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to save processing result for recording $recordingId" }
            throw e // Rethrow to let caller handle the error
        }
    }

    /**
     * Returns a supplier that lazily kicks off a describe-job. Supplier is null when
     * the agent is absent (feature disabled / provider mismatch) OR no frames are available.
     * The supplier itself may still return null if it decides not to start at invocation time.
     */
    private fun buildDescriptionSupplier(
        recordingId: UUID,
        request: SaveProcessingResultRequest,
    ): (() -> Deferred<Result<DescriptionResult>>?)? {
        val agent = descriptionAgentProvider.getIfAvailable() ?: return null

        val common = descriptionProperties.common
        // Mirror FrameVisualizationService ranking (confidence, then detection count) so Claude
        // sees the exact subset the user receives in the media group. Capped by the visualization
        // limit so the AI set is always contained in the user-visible set — see `selectTopFrames`.
        val cap = minOf(common.maxFrames, frameVisualizationService.maxFrames)
        val trimmedFrames =
            frameVisualizationService
                .selectTopFrames(request.frames, cap)
                .sortedBy { it.frameIndex } // chronological order in the prompt, post-selection
                .map { DescriptionRequest.FrameImage(it.frameIndex, it.frameBytes) }

        if (trimmedFrames.isEmpty()) {
            logger.debug { "No frames with detections for recording $recordingId; skipping describe-job" }
            return null
        }

        val descriptionRequest =
            DescriptionRequest(
                recordingId = recordingId,
                frames = trimmedFrames,
                language = common.language,
                shortMaxLength = common.shortMaxLength,
                detailedMaxLength = common.detailedMaxLength,
            )

        return {
            descriptionScope.async {
                try {
                    Result.success(agent.describe(descriptionRequest))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Without this, the exception is swallowed into Result.failure and the user
                    // sees only the localized "Описание недоступно" fallback in Telegram with
                    // nothing in the logs explaining why — making AI failures invisible to ops.
                    logger.warn(e) { "AI description failed for recording $recordingId; users will see fallback" }
                    Result.failure(e)
                }
            }
        }
    }
}
