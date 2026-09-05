package ru.zinin.frigate.analyzer.core.repository

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.junit.jupiter.SpringExtension
import ru.zinin.frigate.analyzer.core.IntegrationTestBase
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.persistent.NotificationVerdictEntity
import ru.zinin.frigate.analyzer.model.persistent.RecordingEntity
import ru.zinin.frigate.analyzer.service.repository.DetectionEntityRepository
import ru.zinin.frigate.analyzer.service.repository.JudgeStatsRepository
import ru.zinin.frigate.analyzer.service.repository.NotificationVerdictRepository
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(SpringExtension::class)
class JudgeStatsRepositoryTest : IntegrationTestBase() {
    @Autowired
    private lateinit var stats: JudgeStatsRepository

    @Autowired
    private lateinit var recordingRepository: RecordingEntityRepository

    @Autowired
    private lateinit var detectionRepository: DetectionEntityRepository

    @Autowired
    private lateinit var verdictRepository: NotificationVerdictRepository

    @BeforeEach
    fun setUp() {
        runBlocking {
            verdictRepository.deleteAll()
            detectionRepository.deleteAll()
            recordingRepository.deleteAll()
        }
    }

    @Test
    fun `staticScore counts recordings and days with IoU at or above the threshold`() {
        runBlocking {
            val day1 = Instant.parse("2026-08-21T12:00:00Z")
            val day2 = Instant.parse("2026-08-22T12:00:00Z")
            val day3 = Instant.parse("2026-08-23T12:00:00Z")
            val poorIouAt = Instant.parse("2026-08-24T12:00:00Z")
            val otherClassAt = Instant.parse("2026-08-25T12:00:00Z")
            val outsideAt = Instant.parse("2026-08-10T12:00:00Z")
            val candidateAt = Instant.parse("2026-08-27T12:00:00Z")

            detection(recording("cam2", day1), className = "car", x1 = 100f, y1 = 100f, x2 = 200f, y2 = 200f)
            detection(recording("cam2", day2), className = "car", x1 = 100f, y1 = 100f, x2 = 200f, y2 = 200f)
            detection(recording("cam2", day3), className = "car", x1 = 100f, y1 = 100f, x2 = 200f, y2 = 200f)
            detection(recording("cam2", poorIouAt), className = "car", x1 = 150f, y1 = 150f, x2 = 250f, y2 = 250f)
            detection(recording("cam2", otherClassAt), className = "person", x1 = 100f, y1 = 100f, x2 = 200f, y2 = 200f)
            detection(recording("cam2", outsideAt), className = "car", x1 = 100f, y1 = 100f, x2 = 200f, y2 = 200f)
            val candidate = recording("cam2", candidateAt)
            detection(candidate, className = "car", x1 = 100f, y1 = 100f, x2 = 200f, y2 = 200f)

            val score =
                stats.staticScore(
                    camId = "cam2",
                    className = "car",
                    x1 = 100.0,
                    y1 = 100.0,
                    x2 = 200.0,
                    y2 = 200.0,
                    from = Instant.parse("2026-08-20T00:00:00Z"),
                    to = Instant.parse("2026-08-28T00:00:00Z"),
                    excludeRecordingId = candidate.id!!,
                    iou = 0.4,
                    zone = "UTC",
                )

            assertEquals(3L, score.recordings)
            assertEquals(3L, score.days)
            assertEquals(day1, score.firstSeen)
            assertEquals(day3, score.lastSeen)
        }
    }

    @Test
    fun `recordingsInWindow counts only this camera inside the window`() {
        runBlocking {
            val from = Instant.parse("2026-09-01T00:00:00Z")
            val to = Instant.parse("2026-09-02T00:00:00Z")
            recording("cam2", Instant.parse("2026-09-01T10:00:00Z"))
            recording("cam2", Instant.parse("2026-09-01T23:59:59Z"))
            recording("cam2", Instant.parse("2026-09-02T00:00:00Z"))
            recording("cam2", Instant.parse("2026-08-31T23:59:59Z"))
            recording("cam3", Instant.parse("2026-09-01T10:00:00Z"))

            val count = stats.recordingsInWindow("cam2", from, to)

            assertEquals(2L, count)
        }
    }

    @Test
    fun `verdictCounters groups by stage, verdict and reason since the instant`() {
        runBlocking {
            val since = Instant.parse("2026-09-05T00:00:00Z")
            seedVerdict(
                createdAt = since.plusSeconds(3600),
                stage = "JUDGE",
                verdict = "PUBLISH",
                reason = "NEW_EVENT",
            )
            seedVerdict(
                createdAt = since.plusSeconds(7200),
                stage = "JUDGE",
                verdict = "PUBLISH",
                reason = "NEW_EVENT",
            )
            seedVerdict(
                createdAt = since.plusSeconds(3600),
                stage = "JUDGE",
                verdict = "SUPPRESS",
                reason = "STATIC_OBJECT",
            )
            seedVerdict(
                createdAt = since.plusSeconds(3600),
                stage = "SNOOZE",
                verdict = "SUPPRESS",
                reason = "SNOOZED",
            )
            seedVerdict(
                createdAt = since.minusSeconds(3600),
                stage = "JUDGE",
                verdict = "PUBLISH",
                reason = "NEW_EVENT",
            )

            val rows = stats.verdictCounters(since).associateBy { Triple(it.stage, it.verdict, it.reason) }

            assertEquals(3, rows.size)
            assertEquals(2L, rows[Triple("JUDGE", "PUBLISH", "NEW_EVENT")]?.count)
            assertEquals(1L, rows[Triple("JUDGE", "SUPPRESS", "STATIC_OBJECT")]?.count)
            assertEquals(1L, rows[Triple("SNOOZE", "SUPPRESS", "SNOOZED")]?.count)
            assertNull(rows[Triple("FAILOVER", "PUBLISH", "TIMEOUT")])
        }
    }

    private suspend fun recording(
        camId: String,
        ts: Instant,
    ): RecordingEntity {
        val atZone = ts.atZone(ZoneOffset.UTC)
        return recordingRepository.save(
            RecordingEntity(
                id = UUID.randomUUID(),
                creationTimestamp = ts,
                filePath = "/recordings/${UUID.randomUUID()}.mp4",
                fileCreationTimestamp = ts,
                camId = camId,
                recordDate = LocalDate.from(atZone),
                recordTime = LocalTime.from(atZone),
                recordTimestamp = ts,
                startProcessingTimestamp = null,
                processTimestamp = null,
                processAttempts = 0,
                detectionsCount = 0,
                analyzeTime = 0,
                analyzedFramesCount = 0,
                errorMessage = null,
            ),
        )
    }

    private suspend fun detection(
        recording: RecordingEntity,
        className: String,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): DetectionEntity {
        val ts = recording.recordTimestamp!!
        return detectionRepository.save(
            DetectionEntity(
                id = UUID.randomUUID(),
                creationTimestamp = ts,
                recordingId = recording.id,
                detectionTimestamp = ts,
                frameIndex = 0,
                model = "yolo26s.pt",
                classId = 2,
                className = className,
                confidence = 0.9f,
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
            ),
        )
    }

    private suspend fun seedVerdict(
        createdAt: Instant,
        stage: String,
        verdict: String,
        reason: String,
    ) {
        val rec = recording("cam2", createdAt)
        verdictRepository.save(
            NotificationVerdictEntity(
                id = UUID.randomUUID(),
                createdAt = createdAt,
                recordingId = rec.id!!,
                camId = "cam2",
                recordTimestamp = createdAt,
                stage = stage,
                verdict = verdict,
                reason = reason,
                trackerReason = "NEW_OBJECTS",
                classes = "car:1",
                confidence = null,
                summary = null,
                wanted = null,
                snoozeUntil = null,
                presetId = null,
                model = null,
                latencyMs = null,
                contextJson = null,
                error = null,
            ),
        )
    }
}
