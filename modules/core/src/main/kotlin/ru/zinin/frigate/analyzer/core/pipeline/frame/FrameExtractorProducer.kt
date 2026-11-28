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
import ru.zinin.frigate.analyzer.model.response.ExtractedFrameData
import ru.zinin.frigate.analyzer.model.response.FrameExtractionResponse
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger {}

/**
 * Summarises how the extraction server arrived at the frames it returned — ` (first=1, grid=3,
 * motion=2)`, empty for an empty list. Since vision-api 3.0 picks frames by motion, this is what
 * answers "why did this recording yield a single frame" without a second request.
 */
internal fun frameReasonSuffix(frames: List<ExtractedFrameData>): String =
    if (frames.isEmpty()) {
        ""
    } else {
        frames
            .groupingBy { it.reason }
            .eachCount()
            .entries
            .joinToString(separator = ", ", prefix = " (", postfix = ")") { (reason, count) -> "$reason=$count" }
    }

/**
 * Кадры сервера в кадры пайплайна. Номер свой, с нуля, в порядке сервера; время — `timestamp`
 * сервера, секунды от начала записи. Без него после перенумерации не осталось бы следа того, когда
 * снят кадр, а раскадровке AI-описания нужно поставить детекции на шкалу времени.
 */
internal fun toFrameData(
    recordId: UUID,
    frames: List<ExtractedFrameData>,
): List<FrameData> {
    val decoder = Base64.getDecoder()
    return frames.mapIndexed { index, frame ->
        FrameData(recordId, index, decoder.decode(frame.imageBase64), offsetSeconds = frame.timestamp)
    }
}

/**
 * Хвост той же строки про сравнение с первым кадром: сколько кадров из `total - 1` уйдут сразу в
 * good-model или почему сравнения не было, и сколько оно заняло: по этому после выката видно, сколько
 * режим стоит по GPU и CPU.
 */
internal fun changedFramesSuffix(
    changes: ChangedFrames,
    total: Int,
    millis: Long,
): String =
    when (changes.skip) {
        null -> "; changed ${changes.indexes.size}/${total - 1} -> good model ($millis ms)"
        ChangedFrames.Skip.MONOCHROME -> "; monochrome, changed-frame check skipped ($millis ms)"
        ChangedFrames.Skip.FAILED -> "; changed-frame check failed ($millis ms)"
        ChangedFrames.Skip.DISABLED, ChangedFrames.Skip.NOTHING_TO_COMPARE -> ""
    }

@Component
class FrameExtractorProducer(
    private val detectService: DetectService,
    private val recordingEntityService: RecordingEntityService,
    private val recordingTracker: RecordingTracker,
    private val pipelineProperties: PipelineProperties,
    private val detectProperties: DetectProperties,
    private val changedFrameDetector: ChangedFrameDetector,
) {
    /**
     * Main producer loop. Extracts frames from video and sends them to the channel.
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
        // Check: is the recording already being processed?
        if (recordingTracker.isRegistered(record.id)) {
            logger.warn {
                "Recording ${record.id} is already being processed, skipping to avoid overwriting state"
            }
            return
        }

        try {
            val response = extractFramesFromVideo(record)
            val frameDataList = toFrameData(record.id, response.frames)
            // Декодирование и сравнение картинок — работа для CPU. Производители и так работают на Dispatchers.Default, и
            // сейчас переключения нет: withContext лишь удержит её вне потоков ввода-вывода при запуске на другом диспетчере.
            val (changes, comparison) =
                measureTimedValue { withContext(Dispatchers.Default) { changedFrameDetector.detect(frameDataList) } }
            logger.info {
                "Extracted ${response.frames.size} frames for recording ${record.id}" +
                    frameReasonSuffix(response.frames) +
                    changedFramesSuffix(changes, frameDataList.size, comparison.inWholeMilliseconds)
            }

            // Still reachable although the server always returns frame 0: the zero-byte file path
            // in extractFramesFromVideo fabricates an empty response without asking the server.
            if (response.frames.isEmpty()) {
                recordingEntityService.saveProcessingResult(
                    SaveProcessingResultRequest(record.id),
                )
                logger.info { "Recording ${record.id} has no frames, marked as processed" }
                return
            }

            recordingTracker.registerRecording(record.id, frameDataList)

            for (frameData in frameDataList) {
                channel.send(
                    FrameTask(
                        frameData.recordId,
                        frameData.frameIndex,
                        frameData.frameBytes,
                        changed = frameData.frameIndex in changes.indexes,
                    ),
                )
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
            recordingEntityService.incrementProcessAttempts(record.id)
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
            maxGap = frameExtractionConfig.maxGap,
            motionThreshold = frameExtractionConfig.motionThreshold,
            minInterval = frameExtractionConfig.minInterval,
            maxFrames = frameExtractionConfig.maxFrames,
        )
    }
}
