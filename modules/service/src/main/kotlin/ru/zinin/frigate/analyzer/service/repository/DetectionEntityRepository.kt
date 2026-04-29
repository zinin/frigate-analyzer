package ru.zinin.frigate.analyzer.service.repository

import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import java.util.UUID

interface DetectionEntityRepository : CoroutineCrudRepository<DetectionEntity, UUID> {
    suspend fun findByRecordingId(recordingId: UUID): List<DetectionEntity>
}
