package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.request.CreateDetectionRequest
import ru.zinin.frigate.analyzer.service.DetectionEntityService
import ru.zinin.frigate.analyzer.service.mapper.DetectionMapper
import ru.zinin.frigate.analyzer.service.repository.DetectionEntityRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class DetectionEntityServiceImpl(
    val mapper: DetectionMapper,
    val repository: DetectionEntityRepository,
    val uuidGeneratorHelper: UUIDGeneratorHelper,
    val clock: Clock,
) : DetectionEntityService {
    @Transactional
    override suspend fun createDetection(request: CreateDetectionRequest): UUID {
        val now = Instant.now(clock)
        val detectionId = uuidGeneratorHelper.generateV1()

        val entity =
            mapper.toEntity(request).apply {
                id = detectionId
                creationTimestamp = now
            }

        repository.save(entity)
        logger.info { "Created detection $detectionId for recording ${request.recordingId}" }

        return detectionId
    }

    @Transactional(readOnly = true)
    override suspend fun findByRecordingId(recordingId: UUID): List<DetectionEntity> = repository.findByRecordingId(recordingId)
}
