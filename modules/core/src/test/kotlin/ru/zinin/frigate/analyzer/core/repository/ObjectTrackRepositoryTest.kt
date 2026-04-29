package ru.zinin.frigate.analyzer.core.repository

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.junit.jupiter.SpringExtension
import ru.zinin.frigate.analyzer.core.IntegrationTestBase
import ru.zinin.frigate.analyzer.model.persistent.ObjectTrackEntity
import ru.zinin.frigate.analyzer.model.persistent.RecordingEntity
import ru.zinin.frigate.analyzer.service.repository.ObjectTrackRepository
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(SpringExtension::class)
class ObjectTrackRepositoryTest : IntegrationTestBase() {
    @Autowired
    private lateinit var repository: ObjectTrackRepository

    @Autowired
    private lateinit var recordingRepository: RecordingEntityRepository

    private val baseTime = Instant.parse("2026-04-27T12:00:00Z")

    @BeforeEach
    fun setUp() {
        runBlocking {
            repository.deleteAll()
            recordingRepository.deleteAll()
        }
    }

    @Test
    fun `findActive filters by camera and bounded last seen timestamp window`() {
        runBlocking {
            val activeFront = track(camId = "front", lastSeenAt = baseTime)
            val nearFutureFront = track(camId = "front", lastSeenAt = baseTime.plusSeconds(60))
            val farFutureFront = track(camId = "front", lastSeenAt = baseTime.plusSeconds(300))
            val oldFront = track(camId = "front", lastSeenAt = baseTime.minusSeconds(300))
            val activeBack = track(camId = "back", lastSeenAt = baseTime)
            repository.save(activeFront)
            repository.save(nearFutureFront)
            repository.save(farFutureFront)
            repository.save(oldFront)
            repository.save(activeBack)

            val result = repository.findActive("front", baseTime.minusSeconds(120), baseTime.plusSeconds(120))

            assertEquals(setOf(activeFront.id, nearFutureFront.id), result.map { it.id }.toSet())
        }
    }

    @Test
    fun `updateOnMatch refreshes bbox last seen and recording id for newer recording`() {
        runBlocking {
            val recording = recording(baseTime)
            recordingRepository.save(recording)
            val track = repository.save(track(lastSeenAt = baseTime.minusSeconds(60)))

            val updated =
                repository.updateOnMatch(
                    id = track.id!!,
                    x1 = 10f,
                    y1 = 11f,
                    x2 = 20f,
                    y2 = 21f,
                    lastSeenAt = baseTime,
                    lastRecordingId = recording.id!!,
                )

            val actual = repository.findById(track.id!!)
            assertEquals(1L, updated)
            assertNotNull(actual)
            assertEquals(10f, actual!!.bboxX1)
            assertEquals(11f, actual.bboxY1)
            assertEquals(20f, actual.bboxX2)
            assertEquals(21f, actual.bboxY2)
            assertEquals(baseTime, actual.lastSeenAt)
            assertEquals(recording.id, actual.lastRecordingId)
        }
    }

    @Test
    fun `updateOnMatch does not roll bbox or recording id backward for older recording`() {
        runBlocking {
            val newerRecording = recording(baseTime.plusSeconds(60))
            val olderRecording = recording(baseTime.minusSeconds(60))
            recordingRepository.save(newerRecording)
            recordingRepository.save(olderRecording)
            val existing =
                repository.save(
                    track(
                        bboxX1 = 1f,
                        bboxY1 = 2f,
                        bboxX2 = 3f,
                        bboxY2 = 4f,
                        lastSeenAt = baseTime,
                        lastRecordingId = newerRecording.id,
                    ),
                )

            val updated =
                repository.updateOnMatch(
                    id = existing.id!!,
                    x1 = 10f,
                    y1 = 11f,
                    x2 = 20f,
                    y2 = 21f,
                    lastSeenAt = baseTime.minusSeconds(60),
                    lastRecordingId = olderRecording.id!!,
                )

            val actual = repository.findById(existing.id!!)
            assertEquals(1L, updated)
            assertNotNull(actual)
            assertEquals(1f, actual!!.bboxX1)
            assertEquals(2f, actual.bboxY1)
            assertEquals(3f, actual.bboxX2)
            assertEquals(4f, actual.bboxY2)
            assertEquals(baseTime, actual.lastSeenAt)
            assertEquals(newerRecording.id, actual.lastRecordingId)
        }
    }

    @Test
    fun `deleteExpired removes only tracks older than threshold`() {
        runBlocking {
            val old = repository.save(track(lastSeenAt = baseTime.minusSeconds(300)))
            val active = repository.save(track(lastSeenAt = baseTime))

            val deleted = repository.deleteExpired(baseTime.minusSeconds(120))

            assertEquals(1L, deleted)
            assertEquals(null, repository.findById(old.id!!))
            assertNotNull(repository.findById(active.id!!))
        }
    }

    private fun track(
        id: UUID = UUID.randomUUID(),
        camId: String = "front",
        className: String = "car",
        bboxX1: Float = 0f,
        bboxY1: Float = 0f,
        bboxX2: Float = 1f,
        bboxY2: Float = 1f,
        lastSeenAt: Instant = baseTime,
        lastRecordingId: UUID? = null,
    ): ObjectTrackEntity =
        ObjectTrackEntity(
            id = id,
            creationTimestamp = lastSeenAt,
            camId = camId,
            className = className,
            bboxX1 = bboxX1,
            bboxY1 = bboxY1,
            bboxX2 = bboxX2,
            bboxY2 = bboxY2,
            lastSeenAt = lastSeenAt,
            lastRecordingId = lastRecordingId,
        )

    private fun recording(recordTimestamp: Instant): RecordingEntity {
        val atZone = recordTimestamp.atZone(ZoneOffset.UTC)
        return RecordingEntity(
            id = UUID.randomUUID(),
            creationTimestamp = recordTimestamp,
            filePath = "/recordings/${UUID.randomUUID()}.mp4",
            fileCreationTimestamp = recordTimestamp,
            camId = "front",
            recordDate = LocalDate.from(atZone),
            recordTime = LocalTime.from(atZone),
            recordTimestamp = recordTimestamp,
            startProcessingTimestamp = null,
            processTimestamp = null,
            processAttempts = 0,
            detectionsCount = 0,
            analyzeTime = 0,
            analyzedFramesCount = 0,
            errorMessage = null,
        )
    }
}
