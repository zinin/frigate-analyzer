package ru.zinin.frigate.analyzer.core.pipeline.frame

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.core.config.properties.PipelineProperties
import ru.zinin.frigate.analyzer.core.service.DetectService
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.exception.UnprocessableVideoException
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import ru.zinin.frigate.analyzer.model.response.FrameExtractionResponse
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Component
class FrameExtractorProducer(
    private val detectService: DetectService,
    private val recordingEntityService: RecordingEntityService,
    private val recordingTracker: RecordingTracker,
    private val pipelineProperties: PipelineProperties,
    private val detectProperties: DetectProperties,
) {
    /**
     * Основной цикл продюсера. Извлекает кадры из видео и отправляет в канал.
     */
    suspend fun produce(
        channel: SendChannel<FrameTask>,
        producerId: Int,
    ) {
        logger.info { "Frame extractor producer started" }
        val producerConfig = pipelineProperties.producer

        while (true) {
            try {
                val hasWork = processNextBatch(channel)

                if (!hasWork) {
                    logger.trace { "No unprocessed recordings, waiting ${producerConfig.idleDelay}..." }
                    delay(producerConfig.idleDelay.toMillis())
                }
            } catch (e: CancellationException) {
                logger.info { "Producer cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error in producer, retrying in ${producerConfig.errorDelay}..." }
                delay(producerConfig.errorDelay.toMillis())
            }
        }
    }

    private suspend fun processNextBatch(channel: SendChannel<FrameTask>): Boolean {
        val recordings = recordingEntityService.findUnprocessedRecordings(limit = pipelineProperties.producer.batchSize)

        if (recordings.isEmpty()) {
            return false
        }

        logger.info { "Found ${recordings.size} unprocessed recordings" }

        for (record in recordings) {
            processRecording(record, channel)
        }

        return true
    }

    private suspend fun processRecording(
        record: RecordingDto,
        channel: SendChannel<FrameTask>,
    ) {
        // Проверка: запись уже обрабатывается?
        if (recordingTracker.isRegistered(record.id)) {
            logger.warn {
                "Recording ${record.id} is already being processed, skipping to avoid overwriting state"
            }
            return
        }

        try {
            recordingEntityService.incrementProcessAttempts(record.id)

            val response = extractFramesFromVideo(record)
            logger.info { "Extracted ${response.frames.size} frames for recording ${record.id}" }

            if (response.frames.isEmpty()) {
                recordingEntityService.saveProcessingResult(
                    SaveProcessingResultRequest(record.id),
                )
                logger.info { "Recording ${record.id} has no frames, marked as processed" }
                return
            }

            val decoder = Base64.getDecoder()
            val frameDataList =
                response.frames.mapIndexed { index, frame ->
                    val frameBytes = decoder.decode(frame.imageBase64)
                    FrameData(record.id, index, frameBytes)
                }

            recordingTracker.registerRecording(record.id, frameDataList)

            for (frameData in frameDataList) {
                channel.send(FrameTask(frameData.recordId, frameData.frameIndex, frameData.frameBytes))
            }

            logger.debug { "Sent ${frameDataList.size} frame tasks for recording ${record.id}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnprocessableVideoException) {
            logger.warn { "Recording ${record.id} has unprocessable video: ${e.message}" }
            recordingEntityService.markProcessedWithError(record.id, e.message ?: "Unknown error")
        } catch (e: NoSuchFileException) {
            logger.warn(e) {
                "Recording ${record.id} file missing (${record.filePath}); deleting recording"
            }
            recordingEntityService.deleteRecording(record.id)
        } catch (e: Exception) {
            logger.error(e) { "Failed to process recording ${record.id}" }
        }
    }

    private suspend fun extractFramesFromVideo(record: RecordingDto): FrameExtractionResponse {
        val videoPath = Path.of(record.filePath)
        val videoBytes =
            withContext(Dispatchers.IO) {
                Files.readAllBytes(videoPath)
            }

        if (videoBytes.isEmpty()) {
            logger.warn { "Video file is empty (0 bytes): ${record.filePath}" }
            return FrameExtractionResponse(
                success = false,
                videoDuration = 0.0,
                videoResolution = emptyList(),
                framesExtracted = 0,
                frames = emptyList(),
                processingTimeMs = 0,
            )
        }

        val frameExtractionConfig = detectProperties.frameExtraction
        return detectService.extractFramesRemoteWithRetry(
            bytes = videoBytes,
            filePath = record.filePath,
            recordingId = record.id,
            sceneThreshold = frameExtractionConfig.sceneThreshold,
            minInterval = frameExtractionConfig.minInterval,
            maxFrames = frameExtractionConfig.maxFrames,
        )
    }
}
