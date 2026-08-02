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
import ru.zinin.frigate.analyzer.model.dto.RepresentativeBbox
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.persistent.ObjectTrackEntity
import ru.zinin.frigate.analyzer.service.ObjectTrackerService
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import ru.zinin.frigate.analyzer.service.helper.BboxClusteringHelper
import ru.zinin.frigate.analyzer.service.helper.IouHelper
import ru.zinin.frigate.analyzer.service.repository.ObjectTrackRepository
import java.time.Clock
import java.time.Duration
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

    // A reappearance is a gap between recording timestamps, but such a gap only means "the object
    // was gone" if the tracker was watching the camera throughout it — and it is not always. The
    // unprocessed queue is drained newest-first (RecordingEntityRepository.findUnprocessedForUpdate)
    // with no floor on age, so after any interruption — restart, deploy, stalled pipeline, camera
    // signal loss — the first recording processed carries the entire interruption as an apparent
    // absence, and every static object still matching by IoU would notify. Note that measuring gaps
    // in `detections`, as the tuning guide suggests, cannot reveal this: the gap is in processing,
    // not in the recordings.
    //
    // This map bounds the claim. The wall clock of the previous evaluation is the only thing
    // that can detect the interruption, since it is about processing rather than content; the
    // recording timestamp the window restarts from is then compared against track timestamps, so
    // the actual test stays inside one time base. Both halves live in one value updated by a
    // single compute(): stamping them separately let a concurrent same-camera evaluation pair a
    // fresh lastEvaluatedAt with a stale watchedSince — a looser window than either write alone.
    private data class Watch(
        val lastEvaluatedAt: Instant,
        val watchedSinceRecordTs: Instant,
    )

    private val watchByCamera = ConcurrentHashMap<String, Watch>()

    override fun markObserved(recording: RecordingDto) {
        // Detection-less recordings reach the tracker through this path only:
        // NotificationDecisionServiceImpl short-circuits to NO_DETECTIONS before evaluate.
        markWatched(recording)
    }

    override suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): DetectionDelta {
        // Stamped for every evaluated recording, including the ones that return empty below: what
        // this records is whether the tracker was watching the camera, not whether it found
        // anything. Recordings with no detections at all never get here — the decision service
        // stamps them via markObserved instead.
        val watchedSince = markWatched(recording)
        if (detections.isEmpty()) {
            return DetectionDelta(
                newTracksCount = 0,
                matchedTracksCount = 0,
                staleTracksCount = 0,
                newClasses = emptyList(),
            )
        }
        // Cluster outside the mutex + transaction: pure CPU work, no DB connection needed.
        // Avoids holding the per-camera lock and a connection-pool slot when all detections
        // are below confidence floor (representatives empty → no DB writes anyway).
        val representatives =
            BboxClusteringHelper.cluster(
                detections,
                properties.innerIou,
                properties.confidenceFloor,
            )
        if (representatives.isEmpty()) {
            return DetectionDelta(
                newTracksCount = 0,
                matchedTracksCount = 0,
                staleTracksCount = 0,
                newClasses = emptyList(),
            )
        }
        val mutex = perCameraMutex.computeIfAbsent(recording.camId) { Mutex() }
        return mutex.withLock {
            transactionalOperator.executeAndAwait {
                evaluateLocked(recording, representatives, watchedSince)
            }
        }
    }

    /**
     * Advances this camera's watch bookkeeping and returns the earliest recording timestamp an
     * absence may reach back to and still describe something this process observed.
     *
     * A wall-clock gap between consecutive stamps longer than
     * [ObjectTrackerProperties.reappearGap] means the tracker was not watching — an absence that
     * long is exactly what it would otherwise report — so the window restarts at this recording.
     * The whole decision runs inside a single compute(), so concurrent same-camera calls (the
     * mutex is taken later) serialize per camera and can never pair a fresh lastEvaluatedAt with
     * a stale watchedSince. On a restart the window never moves backwards (maxOf): an out-of-order
     * recording — e.g. a stuck one re-picked after its cooldown — cannot reopen an older window.
     *
     * Residual imprecision, accepted: the window restarts at whichever backlog recording happens
     * to be stamped first after the interruption. The drain is newest-first, but SKIP LOCKED
     * spreads batches across producers, so a slightly older recording can win and leave the
     * window marginally wider than the ideal (newest-of-backlog). Closing that would require the
     * tracker to consult the recording queue; the monotonic guard above plus the ordering keep
     * the exposure to the first seconds after a restart.
     *
     * Two more accepted trade-offs. The interruption threshold IS the reappear gap, so an
     * in-process stall shorter than the gap keeps the window open and the first absence measured
     * across it may notify falsely — one extra notification, consistent with the tracker's
     * fail-open bias (see the "Known limitation" note in configuration.md). And the stamp means
     * "an evaluation was attempted", not "it succeeded": tracker-only failures sustained longer
     * than the gap keep the window open while lastSeenAt stagnates, which can add one reappearance
     * burst after recovery — drowned out by the per-recording TRACKER_ERROR fail-open
     * notifications such an outage already produces.
     */
    private fun markWatched(recording: RecordingDto): Instant =
        watchByCamera
            .compute(recording.camId) { _, previous ->
                val now = Instant.now(clock)
                val interrupted =
                    previous == null ||
                        Duration.between(previous.lastEvaluatedAt, now) > properties.reappearGap
                val watchedSince =
                    if (previous == null) {
                        recording.recordTimestamp
                    } else if (interrupted) {
                        maxOf(previous.watchedSinceRecordTs, recording.recordTimestamp)
                    } else {
                        previous.watchedSinceRecordTs
                    }
                Watch(lastEvaluatedAt = now, watchedSinceRecordTs = watchedSince)
            }!!
            .watchedSinceRecordTs

    private suspend fun evaluateLocked(
        recording: RecordingDto,
        representatives: List<RepresentativeBbox>,
        watchedSince: Instant,
    ): DetectionDelta {
        val recordingTimestamp = recording.recordTimestamp
        val ttlThreshold = recordingTimestamp.minus(properties.ttl)
        val ttlUpperBound = recordingTimestamp.plus(properties.ttl)
        val active = repository.findActive(recording.camId, ttlThreshold, ttlUpperBound).toMutableList()

        var matched = 0
        var unobservedAbsences = 0
        val newClasses = mutableListOf<String>()
        val reappearedClasses = mutableListOf<String>()
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
                // Measured against the pre-update lastSeenAt: how long this track was absent before now.
                // Negative for out-of-order (older) recordings, which therefore never count as a
                // reappearance — the later recording that already advanced the track had its own say.
                // lastSeenAt is nullable on the entity but never null here: findActive filters on it.
                val lastSeen = match.lastSeenAt
                val absence = lastSeen?.let { Duration.between(it, recordingTimestamp) }
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
                // Strictly greater, not >=: findActive's lower bound is inclusive
                // (last_seen_at >= recordingTimestamp - ttl), so the largest absence a matched track
                // can show is exactly ttl. Demanding more than the gap is what keeps the default
                // reappearGap == ttl unreachable, and so the no-op it is documented as.
                if (lastSeen != null && absence != null && absence > properties.reappearGap) {
                    // An absence that began before this camera's watch window is not evidence the
                    // object left — only that nobody was looking. Checked per track rather than per
                    // recording: a static object the detector misses in the first frame after an
                    // interruption would otherwise slip through on the next one.
                    if (lastSeen.isBefore(watchedSince)) {
                        unobservedAbsences++
                    } else {
                        reappearedClasses += bbox.className
                    }
                }
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
        if (newCount > 0 || reappearedClasses.isNotEmpty() || unobservedAbsences > 0) {
            logger.debug {
                "ObjectTracker: cam=${recording.camId} new=$newCount matched=$matched " +
                    "reappeared=${reappearedClasses.size} unobserved=$unobservedAbsences " +
                    "stale=${active.size} (recording=${recording.id})"
            }
        }
        return DetectionDelta(
            newTracksCount = newCount,
            matchedTracksCount = matched,
            staleTracksCount = active.size,
            newClasses = newClasses,
            reappearedTracksCount = reappearedClasses.size,
            reappearedClasses = reappearedClasses,
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
