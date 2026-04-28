package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.DetectionDelta
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.persistent.ObjectTrackEntity
import ru.zinin.frigate.analyzer.service.ObjectTrackerService
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import ru.zinin.frigate.analyzer.service.helper.BboxClusteringHelper
import ru.zinin.frigate.analyzer.service.helper.IouHelper
import ru.zinin.frigate.analyzer.service.repository.ObjectTrackRepository
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class ObjectTrackerServiceImpl(
    private val repository: ObjectTrackRepository,
    private val uuid: UUIDGeneratorHelper,
    private val clock: Clock,
    private val properties: ObjectTrackerProperties,
    private val transactionalOperator: TransactionalOperator,
) : ObjectTrackerService {
    // Camera set is static for this single-instance deployment.
    private val perCameraMutex = ConcurrentHashMap<String, Mutex>()

    override suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): DetectionDelta {
        if (detections.isEmpty()) {
            return DetectionDelta(0, 0, 0, emptyList())
        }
        val mutex = perCameraMutex.computeIfAbsent(recording.camId) { Mutex() }
        return mutex.withLock {
            transactionalOperator.executeAndAwait {
                evaluateLocked(recording, detections)
            }
        }
    }

    private suspend fun evaluateLocked(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): DetectionDelta {
        if (detections.isEmpty()) {
            return DetectionDelta(0, 0, 0, emptyList())
        }
        val representatives =
            BboxClusteringHelper.cluster(
                detections,
                properties.innerIou,
                properties.confidenceFloor,
            )
        if (representatives.isEmpty()) {
            return DetectionDelta(0, 0, 0, emptyList())
        }
        val recordingTimestamp =
            requireNotNull(recording.recordTimestamp) {
                "RecordingDto.recordTimestamp is null for recording=${recording.id}"
            }
        val ttlThreshold = recordingTimestamp.minus(properties.ttl)
        val active = repository.findActive(recording.camId, ttlThreshold).toMutableList()

        var matched = 0
        val newClasses = mutableListOf<String>()
        for (bbox in representatives) {
            val match =
                active
                    .filter { it.className == bbox.className }
                    .mapNotNull { track ->
                        val iouVal =
                            IouHelper.iou(
                                track.bboxX1,
                                track.bboxY1,
                                track.bboxX2,
                                track.bboxY2,
                                bbox.x1,
                                bbox.y1,
                                bbox.x2,
                                bbox.y2,
                            )
                        if (iouVal > properties.iouThreshold) track to iouVal else null
                    }.maxByOrNull { (_, iouVal) -> iouVal }
                    ?.first
            if (match != null) {
                active.remove(match)
                val matchId = requireNotNull(match.id) { "ObjectTrackEntity.id is null for matched track" }
                val updated =
                    repository.updateOnMatch(
                        id = matchId,
                        x1 = bbox.x1,
                        y1 = bbox.y1,
                        x2 = bbox.x2,
                        y2 = bbox.y2,
                        lastSeenAt = recordingTimestamp,
                        lastRecordingId = recording.id,
                    )
                check(updated == 1L) { "Object track $matchId disappeared before update" }
                matched++
            } else {
                repository.save(
                    ObjectTrackEntity(
                        id = uuid.generateV1(),
                        creationTimestamp = recordingTimestamp,
                        camId = recording.camId,
                        className = bbox.className,
                        bboxX1 = bbox.x1,
                        bboxY1 = bbox.y1,
                        bboxX2 = bbox.x2,
                        bboxY2 = bbox.y2,
                        lastSeenAt = recordingTimestamp,
                        lastRecordingId = recording.id,
                    ),
                )
                newClasses += bbox.className
            }
        }
        val newCount = newClasses.size
        if (newCount > 0) {
            logger.debug {
                "ObjectTracker: cam=${recording.camId} new=$newCount matched=$matched stale=${active.size} " +
                    "(recording=${recording.id})"
            }
        }
        return DetectionDelta(
            newTracksCount = newCount,
            matchedTracksCount = matched,
            staleTracksCount = active.size,
            newClasses = newClasses,
        )
    }

    override suspend fun cleanupExpired(): Long {
        val threshold = Instant.now(clock).minus(properties.cleanupRetention)
        val deleted = repository.deleteExpired(threshold)
        if (deleted > 0) {
            logger.info { "ObjectTracker cleanup: deleted $deleted expired tracks (older than $threshold)" }
        }
        return deleted
    }
}
