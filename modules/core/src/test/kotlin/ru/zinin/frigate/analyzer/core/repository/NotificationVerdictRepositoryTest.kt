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
import ru.zinin.frigate.analyzer.model.persistent.NotificationVerdictEntity
import ru.zinin.frigate.analyzer.model.persistent.RecordingEntity
import ru.zinin.frigate.analyzer.service.repository.NotificationVerdictRepository
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(SpringExtension::class)
class NotificationVerdictRepositoryTest : IntegrationTestBase() {
    @Autowired
    private lateinit var repository: NotificationVerdictRepository

    @Autowired
    private lateinit var recordingRepository: RecordingEntityRepository

    private val ts = Instant.parse("2026-09-05T12:00:00Z")

    @BeforeEach
    fun setUp() {
        runBlocking {
            repository.deleteAll()
            recordingRepository.deleteAll()
        }
    }

    @Test
    fun `findRecentForCamera respects camera, window on both sides and limit`() {
        runBlocking {
            verdict("cam2", ts.minus(Duration.ofHours(7)))
            val insideOlder = verdict("cam2", ts.minus(Duration.ofHours(1)))
            val insideNewer = verdict("cam2", ts.plus(Duration.ofHours(1)))
            verdict("cam2", ts.plus(Duration.ofHours(7)))
            verdict("cam3", ts)

            val result =
                repository.findRecentForCamera(
                    camId = "cam2",
                    from = ts.minus(Duration.ofHours(6)),
                    to = ts.plus(Duration.ofHours(6)),
                    limit = 10,
                )

            assertEquals(listOf(insideNewer.id, insideOlder.id), result.map { it.id })
        }
    }

    @Test
    fun `findLastPublished returns the newest PUBLISH row of the camera`() {
        runBlocking {
            verdict("cam2", ts.minus(Duration.ofHours(2)), verdict = "PUBLISH", reason = "NEW_EVENT")
            val newestPublish =
                verdict("cam2", ts.minus(Duration.ofHours(1)), verdict = "PUBLISH", reason = "CHANGED_SITUATION")
            verdict("cam2", ts, verdict = "SUPPRESS", reason = "DUPLICATE")
            verdict("cam3", ts.plus(Duration.ofHours(1)), verdict = "PUBLISH", reason = "NEW_EVENT")

            val result = repository.findLastPublished("cam2")

            assertEquals(newestPublish.id, result?.id)
        }
    }

    @Test
    fun `findLatest and findLatestByCamera order by record_timestamp desc`() {
        runBlocking {
            val cam2Old = verdict("cam2", ts.minus(Duration.ofHours(2)))
            val cam3 = verdict("cam3", ts.minus(Duration.ofHours(1)))
            val cam2New = verdict("cam2", ts)

            val latest = repository.findLatest(limit = 3)
            assertEquals(listOf(cam2New.id, cam3.id, cam2Old.id), latest.map { it.id })

            val latestCam2 = repository.findLatestByCamera("cam2", limit = 10)
            assertEquals(listOf(cam2New.id, cam2Old.id), latestCam2.map { it.id })

            val limited = repository.findLatest(limit = 1)
            assertEquals(listOf(cam2New.id), limited.map { it.id })
        }
    }

    @Test
    fun `deleting a recording cascades to its verdicts`() {
        runBlocking {
            val rec = recording("cam2", ts)
            val saved = verdict("cam2", ts, recordingId = rec.id!!)

            recordingRepository.deleteById(rec.id!!)

            assertNull(repository.findById(saved.id!!))
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

    private suspend fun verdict(
        camId: String,
        ts: Instant,
        verdict: String = "SUPPRESS",
        stage: String = "JUDGE",
        reason: String = "DUPLICATE",
        recordingId: UUID? = null,
    ): NotificationVerdictEntity {
        val recId = recordingId ?: recording(camId, ts).id!!
        return repository.save(
            NotificationVerdictEntity(
                id = UUID.randomUUID(),
                createdAt = ts,
                recordingId = recId,
                camId = camId,
                recordTimestamp = ts,
                stage = stage,
                verdict = verdict,
                reason = reason,
                trackerReason = "REAPPEARED",
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
