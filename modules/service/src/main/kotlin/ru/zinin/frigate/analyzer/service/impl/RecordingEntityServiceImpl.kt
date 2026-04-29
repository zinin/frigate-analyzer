package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.request.CreateDetectionRequest
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import ru.zinin.frigate.analyzer.service.DetectionEntityService
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import ru.zinin.frigate.analyzer.service.SavedProcessingResult
import ru.zinin.frigate.analyzer.service.mapper.RecordingMapper
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class RecordingEntityServiceImpl(
    val mapper: RecordingMapper,
    val repository: RecordingEntityRepository,
    val uuidGeneratorHelper: UUIDGeneratorHelper,
    val clock: Clock,
    val detectionService: DetectionEntityService,
) : RecordingEntityService {
    @Transactional
    override suspend fun createRecording(request: CreateRecordingRequest): UUID {
        repository.findByFilePath(request.filePath)?.let {
            logger.warn { "Recording already exists for file ${request.filePath} id=${it.id}" }
            return it.id!!
        }

        val now = Instant.now(clock)
        val recordingId = uuidGeneratorHelper.generateV1()

        val entity =
            mapper.toEntity(request).apply {
                id = recordingId
                creationTimestamp = now
            }

        repository.save(entity)
        return recordingId
    }

    @Transactional
    override suspend fun findUnprocessedRecordings(limit: Int): List<RecordingDto> {
        val fifteenMinusAgo = clock.instant().minus(15, ChronoUnit.MINUTES)
        val createdBefore = clock.instant().minusSeconds(30)

        val result = repository.findUnprocessedForUpdate(fifteenMinusAgo, createdBefore, limit)
        result.forEach {
            repository.startProcessing(it.id!!, clock.instant())
        }

        return result.map { mapper.toDto(it) }
    }

    @Transactional
    override suspend fun saveProcessingResult(request: SaveProcessingResultRequest): SavedProcessingResult {
        val recording =
            repository.findById(request.recordingId)
                ?: throw IllegalArgumentException("Recording with id ${request.recordingId} not found")

        var totalAnalyzeTime = 0L
        var detectionsCount = 0
        val savedDetections = mutableListOf<DetectionEntity>()

        for (frame in request.frames) {
            val detectResponse = frame.detectResponse ?: continue
            totalAnalyzeTime += detectResponse.processingTime

            for (detection in detectResponse.detections) {
                val detectionRequest =
                    CreateDetectionRequest(
                        recordingId = request.recordingId,
                        detectionTimestamp = recording.recordTimestamp!!,
                        frameIndex = frame.frameIndex,
                        model = detectResponse.model,
                        classId = detection.classId,
                        className = detection.className,
                        confidence = detection.confidence.toFloat(),
                        x1 = detection.bbox.x1.toFloat(),
                        y1 = detection.bbox.y1.toFloat(),
                        x2 = detection.bbox.x2.toFloat(),
                        y2 = detection.bbox.y2.toFloat(),
                    )

                savedDetections += detectionService.createDetection(detectionRequest)
                detectionsCount++
            }
        }

        val processTimestamp = Instant.now(clock)
        val analyzeTime = totalAnalyzeTime.toInt()

        repository.markProcessed(
            recordingId = request.recordingId,
            processTimestamp = processTimestamp,
            detectionsCount,
            analyzeTime = analyzeTime,
            request.frames.size,
        )

        val savedRecording =
            mapper.toDto(
                recording.copy(
                    processTimestamp = processTimestamp,
                    processAttempts = (recording.processAttempts ?: 0) + 1,
                    detectionsCount = detectionsCount,
                    analyzeTime = (recording.analyzeTime ?: 0) + analyzeTime,
                    analyzedFramesCount = request.frames.size,
                ),
            )

        logger.info { "Saved processing result for recording ${request.recordingId}" }

        return SavedProcessingResult(
            recording = savedRecording,
            detections = savedDetections,
        )
    }

    @Transactional(readOnly = true)
    override suspend fun getRecording(id: UUID): RecordingDto? = repository.findById(id)?.let { mapper.toDto(it) }

    @Transactional
    override suspend fun deleteRecording(id: UUID) {
        repository.deleteById(id)
        logger.info { "Deleted recording $id" }
    }

    @Transactional
    override suspend fun incrementProcessAttempts(id: UUID) {
        repository.incrementProcessAttempts(id)
    }

    @Transactional
    override suspend fun markProcessedWithError(
        id: UUID,
        errorMessage: String,
    ) {
        val truncated = errorMessage.take(65536)
        repository.markProcessedWithError(id, Instant.now(clock), truncated)
        logger.warn { "Recording $id marked as failed: ${truncated.take(512)}" }
    }
}
