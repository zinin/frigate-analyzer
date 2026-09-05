package ru.zinin.frigate.analyzer.core.judge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.NotificationDecision
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.RepresentativeBbox
import ru.zinin.frigate.analyzer.model.persistent.NotificationVerdictEntity
import ru.zinin.frigate.analyzer.model.response.BBox
import ru.zinin.frigate.analyzer.service.NotificationVerdictService
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import ru.zinin.frigate.analyzer.service.repository.JudgeStatsRepository
import ru.zinin.frigate.analyzer.service.repository.ObjectTrackRepository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.NullNode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class JudgeContextBuilder(
    private val stats: JudgeStatsRepository,
    private val tracks: ObjectTrackRepository,
    private val verdicts: NotificationVerdictService,
    private val properties: JudgeProperties,
    private val trackerProperties: ObjectTrackerProperties,
    private val mapper: JsonMapper,
) {
    suspend fun build(
        candidate: JudgeCandidate,
        objects: List<RepresentativeBbox>,
        zone: ZoneId,
    ): JudgeContextResult {
        val errors = mutableListOf<String>()
        val recording = candidate.recording
        val ts = recording.recordTimestamp
        val root = mapper.createObjectNode()
        root.set(
            "recording",
            mapper.valueToTree(
                RecordingBlock(
                    recording.camId,
                    format(ts, zone),
                    zone.id,
                    recording.processTimestamp?.let { Duration.between(ts, it).seconds },
                ),
            ),
        )
        root.set("frames", mapper.valueToTree(framesBlock(candidate.frames)))

        var cachedWindow: Long? = null

        suspend fun recordingsInWindow(
            from: Instant,
            until: Instant,
        ): Long {
            cachedWindow?.let { return it }
            return stats.recordingsInWindow(recording.camId, from, until).also { cachedWindow = it }
        }
        root.set(
            "objects",
            mapper.valueToTree(objects.map { objectBlock(candidate, it, zone, errors, ::recordingsInWindow) }),
        )
        root.set("tracker", mapper.valueToTree(trackerBlock(candidate.decision)))
        root.set("active_tracks", block("active_tracks", errors) { activeTracks(candidate, zone) })
        root.set("recent_verdicts", block("recent_verdicts", errors) { recentVerdicts(recording, zone) })
        root.set(
            "last_published",
            block("last_published", errors) { verdicts.lastPublished(recording.camId)?.let { verdictBlock(it, zone) } },
        )
        root.put("camera_notes", properties.cameras[recording.camId]?.notes.orEmpty())
        return JudgeContextResult(mapper.writeValueAsString(root), errors)
    }

    private suspend fun objectBlock(
        candidate: JudgeCandidate,
        obj: RepresentativeBbox,
        zone: ZoneId,
        errors: MutableList<String>,
        recordingsInWindow: suspend (Instant, Instant) -> Long,
    ): ObjectBlock {
        val ts = candidate.recording.recordTimestamp
        val ofClass = candidate.detections.filter { it.className == obj.className }
        val confidence = ofClass.maxOfOrNull { it.confidence.toDouble() } ?: 0.0
        val framesSeen = ofClass.map { it.frameIndex }.distinct().size
        val static =
            try {
                val from = ts.minus(properties.staticWindow)
                val score =
                    stats.staticScore(
                        candidate.recording.camId,
                        obj.className,
                        obj.x1.toDouble(),
                        obj.y1.toDouble(),
                        obj.x2.toDouble(),
                        obj.y2.toDouble(),
                        from,
                        ts,
                        candidate.recording.id,
                        properties.staticIou,
                        zone.id,
                    )
                StaticBlock(
                    score.recordings,
                    score.days,
                    score.firstSeen?.let { format(it, zone) },
                    score.lastSeen?.let { format(it, zone) },
                    recordingsInWindow(from, ts),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Judge context: static score failed for ${candidate.recording.id}" }
                errors += "objects.static"
                ErrorBlock(e::class.simpleName ?: "Exception")
            }
        return ObjectBlock(obj.className, confidence, bbox(obj.x1, obj.y1, obj.x2, obj.y2), framesSeen, static)
    }

    private fun framesBlock(frames: List<FrameData>): List<FrameBlock> =
        frames
            .mapNotNull { frame ->
                val response = frame.detectResponse ?: return@mapNotNull null
                FrameBlock(
                    index = frame.frameIndex,
                    width = response.imageSize.width,
                    height = response.imageSize.height,
                    detections =
                        response.detections.map { detection ->
                            FrameDetectionBlock(detection.className, detection.confidence, bbox(detection.bbox))
                        },
                )
            }.sortedBy { it.index }

    private fun trackerBlock(decision: NotificationDecision): TrackerBlock =
        TrackerBlock(
            reason = decision.reason.name,
            newClasses = decision.delta?.newClasses ?: emptyList(),
            reappearedClasses = decision.delta?.reappearedClasses ?: emptyList(),
            maxAbsence = decision.delta?.maxAbsence?.toString(),
        )

    private suspend fun activeTracks(
        candidate: JudgeCandidate,
        zone: ZoneId,
    ): List<ActiveTrackBlock> {
        val ts = candidate.recording.recordTimestamp
        val ttl = trackerProperties.ttl
        return tracks.findActive(candidate.recording.camId, ts.minus(ttl), ts.plus(ttl)).map { track ->
            ActiveTrackBlock(
                className = track.className.orEmpty(),
                bbox = bbox(track.bboxX1, track.bboxY1, track.bboxX2, track.bboxY2),
                firstSeen = track.creationTimestamp?.let { format(it, zone) },
                lastSeen = track.lastSeenAt?.let { format(it, zone) },
                matchedNow = track.lastRecordingId == candidate.recording.id,
            )
        }
    }

    private suspend fun recentVerdicts(
        recording: RecordingDto,
        zone: ZoneId,
    ): List<VerdictBlock> {
        val ts = recording.recordTimestamp
        return verdicts
            .recentForCamera(
                recording.camId,
                ts.minus(properties.historyWindow),
                ts.plus(properties.historyWindow),
                properties.historyLimit,
            ).map { verdictBlock(it, zone) }
    }

    private fun verdictBlock(
        entity: NotificationVerdictEntity,
        zone: ZoneId,
    ): VerdictBlock =
        VerdictBlock(
            time = format(entity.recordTimestamp, zone),
            stage = entity.stage,
            verdict = entity.verdict,
            reason = entity.reason,
            classes = entity.classes,
            summary = entity.summary,
        )

    private suspend fun block(
        name: String,
        errors: MutableList<String>,
        provider: suspend () -> Any?,
    ): JsonNode =
        try {
            val result = provider()
            if (result == null) NullNode.instance else mapper.valueToTree<JsonNode>(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Judge context: $name failed" }
            errors += name
            mapper.valueToTree<JsonNode>(ErrorBlock(e::class.simpleName ?: "Exception"))
        }

    private fun format(
        instant: Instant,
        zone: ZoneId,
    ): String = instant.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun bbox(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): List<Int> = listOf(x1.roundToInt(), y1.roundToInt(), x2.roundToInt(), y2.roundToInt())

    private fun bbox(box: BBox): List<Int> = listOf(box.x1.roundToInt(), box.y1.roundToInt(), box.x2.roundToInt(), box.y2.roundToInt())
}
