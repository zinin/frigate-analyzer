package ru.zinin.frigate.analyzer.core.repository

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.junit.jupiter.SpringExtension
import ru.zinin.frigate.analyzer.core.IntegrationTestBase
import ru.zinin.frigate.analyzer.model.persistent.RecordingEntity
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@ExtendWith(SpringExtension::class)
class RecordingEntityRepositoryTest : IntegrationTestBase() {
    @Autowired
    private lateinit var repository: RecordingEntityRepository

    @BeforeEach
    fun setUp() {
        runBlocking {
            repository.deleteAll()
        }
    }

    // region Helper methods

    private fun createRecordingEntity(
        id: UUID = UUID.randomUUID(),
        filePath: String = "/recordings/${UUID.randomUUID()}.mp4",
        camId: String? = "cam1",
        processTimestamp: Instant? = null,
        startProcessingTimestamp: Instant? = null,
        detectionsCount: Int? = 0,
        analyzeTime: Int? = 0,
        analyzedFramesCount: Int? = 0,
        processAttempts: Int? = 0,
    ): RecordingEntity {
        val now = Instant.now()
        return RecordingEntity(
            id = id,
            creationTimestamp = now,
            filePath = filePath,
            fileCreationTimestamp = now,
            camId = camId,
            recordDate = LocalDate.now(),
            recordTime = LocalTime.now(),
            recordTimestamp = now,
            startProcessingTimestamp = startProcessingTimestamp,
            processTimestamp = processTimestamp,
            processAttempts = processAttempts,
            detectionsCount = detectionsCount,
            analyzeTime = analyzeTime,
            analyzedFramesCount = analyzedFramesCount,
            errorMessage = null,
        )
    }

    // endregion

    // region CRUD operations

    @Test
    fun `should save and find recording by id`() {
        runBlocking {
            // given
            val entity = createRecordingEntity()

            // when
            val saved = repository.save(entity)
            val found = repository.findById(saved.id!!)

            // then
            assertNotNull(found)
            assertEquals(saved.id, found!!.id)
            assertEquals(saved.filePath, found.filePath)
            assertEquals(saved.camId, found.camId)
        }
    }

    @Test
    fun `should find recording by file path`() {
        runBlocking {
            // given
            val filePath = "/recordings/test-file.mp4"
            val entity = createRecordingEntity(filePath = filePath)
            repository.save(entity)

            // when
            val found = repository.findByFilePath(filePath)

            // then
            assertNotNull(found)
            assertEquals(filePath, found!!.filePath)
        }
    }

    @Test
    fun `should return null when file path not found`() {
        runBlocking {
            // when
            val found = repository.findByFilePath("/non-existent/path.mp4")

            // then
            assertNull(found)
        }
    }

    // endregion

    // region findUnprocessedForUpdate

    @Test
    fun `should find unprocessed recordings`() {
        runBlocking {
            // given
            val unprocessed = createRecordingEntity(filePath = "/recordings/unprocessed.mp4")
            val processed =
                createRecordingEntity(
                    filePath = "/recordings/processed.mp4",
                    processTimestamp = Instant.now(),
                )
            repository.save(unprocessed)
            repository.save(processed)

            // when
            val result =
                repository.findUnprocessedForUpdate(
                    stuckBefore = Instant.now().minusSeconds(3600),
                    createdBefore = Instant.now(),
                    limit = 10,
                )

            // then
            assertEquals(1, result.size)
            assertEquals(unprocessed.filePath, result[0].filePath)
        }
    }

    @Test
    fun `should filter by stuckBefore timestamp`() {
        runBlocking {
            // given
            val now = Instant.now()
            val stuckRecording =
                createRecordingEntity(
                    filePath = "/recordings/stuck.mp4",
                    startProcessingTimestamp = now.minusSeconds(7200), // 2 hours ago
                )
            val recentRecording =
                createRecordingEntity(
                    filePath = "/recordings/recent.mp4",
                    startProcessingTimestamp = now.minusSeconds(60), // 1 minute ago
                )
            repository.save(stuckRecording)
            repository.save(recentRecording)

            // when
            val result =
                repository.findUnprocessedForUpdate(
                    stuckBefore = now.minusSeconds(3600), // 1 hour threshold
                    createdBefore = Instant.now(),
                    limit = 10,
                )

            // then
            assertEquals(1, result.size)
            assertEquals(stuckRecording.filePath, result[0].filePath)
        }
    }

    @Test
    fun `should respect limit parameter`() {
        runBlocking {
            // given
            repeat(5) { i ->
                repository.save(createRecordingEntity(filePath = "/recordings/file$i.mp4"))
            }

            // when
            val result =
                repository.findUnprocessedForUpdate(
                    stuckBefore = Instant.now().minusSeconds(3600),
                    createdBefore = Instant.now(),
                    limit = 3,
                )

            // then
            assertEquals(3, result.size)
        }
    }

    @Test
    fun `should order by file_creation_timestamp descending`() {
        runBlocking {
            // given
            val now = Instant.now()
            val older =
                createRecordingEntity(filePath = "/recordings/older.mp4")
                    .copy(fileCreationTimestamp = now.minusSeconds(3600))
            val newer =
                createRecordingEntity(filePath = "/recordings/newer.mp4")
                    .copy(fileCreationTimestamp = now)
            repository.save(older)
            repository.save(newer)

            // when
            val result =
                repository.findUnprocessedForUpdate(
                    stuckBefore = now.minusSeconds(60),
                    createdBefore = Instant.now(),
                    limit = 10,
                )

            // then
            assertEquals(2, result.size)
            assertEquals(newer.filePath, result[0].filePath)
            assertEquals(older.filePath, result[1].filePath)
        }
    }

    // endregion

    // region startProcessing

    @Test
    fun `should update start_processing_timestamp`() {
        runBlocking {
            // given
            val entity = createRecordingEntity()
            val saved = repository.save(entity)
            val processingTime = Instant.now()

            // when
            val updatedCount = repository.startProcessing(saved.id!!, processingTime)

            // then
            assertEquals(1L, updatedCount)
            val updated = repository.findById(saved.id!!)
            assertNotNull(updated)
            assertEquals(
                processingTime.truncatedTo(ChronoUnit.MILLIS),
                updated!!.startProcessingTimestamp?.truncatedTo(ChronoUnit.MILLIS),
            )
        }
    }

    // endregion

    // region markProcessed

    @Test
    fun `should update all processing fields`() {
        runBlocking {
            // given
            val entity = createRecordingEntity(processAttempts = 0, analyzeTime = 0)
            val saved = repository.save(entity)
            val processTimestamp = Instant.now()

            // when
            val updatedCount =
                repository.markProcessed(
                    recordingId = saved.id!!,
                    processTimestamp = processTimestamp,
                    detectionsCount = 5,
                    analyzeTime = 1000,
                    analyzedFramesCount = 10,
                )

            // then
            assertEquals(1L, updatedCount)
            val updated = repository.findById(saved.id!!)
            assertNotNull(updated)
            assertEquals(
                processTimestamp.truncatedTo(ChronoUnit.MILLIS),
                updated!!.processTimestamp?.truncatedTo(ChronoUnit.MILLIS),
            )
            assertEquals(5, updated.detectionsCount)
            assertEquals(1000, updated.analyzeTime)
            assertEquals(10, updated.analyzedFramesCount)
            assertEquals(1, updated.processAttempts)
        }
    }

    @Test
    fun `should increment process_attempts on each call`() {
        runBlocking {
            // given
            val entity = createRecordingEntity(processAttempts = 0, analyzeTime = 0)
            val saved = repository.save(entity)

            // when - first processing
            repository.markProcessed(
                recordingId = saved.id!!,
                processTimestamp = Instant.now(),
                detectionsCount = 0,
                analyzeTime = 100,
                analyzedFramesCount = 5,
            )

            // then
            val afterFirst = repository.findById(saved.id!!)
            assertEquals(1, afterFirst!!.processAttempts)
        }
    }

    @Test
    fun `should accumulate analyze_time`() {
        runBlocking {
            // given
            val entity = createRecordingEntity(processAttempts = 0, analyzeTime = 500)
            val saved = repository.save(entity)

            // when
            repository.markProcessed(
                recordingId = saved.id!!,
                processTimestamp = Instant.now(),
                detectionsCount = 0,
                analyzeTime = 300,
                analyzedFramesCount = 5,
            )

            // then
            val updated = repository.findById(saved.id!!)
            assertEquals(800, updated!!.analyzeTime)
        }
    }

    // endregion

    // region incrementProcessAttempts

    @Test
    fun `should increment process_attempts without changing other fields`() {
        runBlocking {
            // given
            val entity = createRecordingEntity(processAttempts = 0, analyzeTime = 0)
            val saved = repository.save(entity)

            // when
            repository.incrementProcessAttempts(saved.id!!)

            // then
            val updated = repository.findById(saved.id!!)
            assertNotNull(updated)
            assertEquals(1, updated!!.processAttempts)
            assertNull(updated.processTimestamp)
            assertNull(updated.errorMessage)
        }
    }

    @Test
    fun `should increment process_attempts multiple times`() {
        runBlocking {
            // given
            val entity = createRecordingEntity(processAttempts = 0, analyzeTime = 0)
            val saved = repository.save(entity)

            // when
            repository.incrementProcessAttempts(saved.id!!)
            repository.incrementProcessAttempts(saved.id!!)
            repository.incrementProcessAttempts(saved.id!!)

            // then
            val updated = repository.findById(saved.id!!)
            assertEquals(3, updated!!.processAttempts)
        }
    }

    // endregion

    // region markProcessedWithError

    @Test
    fun `should mark recording as processed with error message`() {
        runBlocking {
            // given
            val entity = createRecordingEntity(processAttempts = 0, analyzeTime = 0)
            val saved = repository.save(entity)
            val processTimestamp = Instant.now()

            // when
            val updatedCount =
                repository.markProcessedWithError(
                    saved.id!!,
                    processTimestamp,
                    "File contains no video stream",
                )

            // then
            assertEquals(1L, updatedCount)
            val updated = repository.findById(saved.id!!)
            assertNotNull(updated)
            assertNotNull(updated!!.processTimestamp)
            assertEquals(1, updated.processAttempts)
            assertEquals("File contains no video stream", updated.errorMessage)
        }
    }

    @Test
    fun `should not return error recordings in findUnprocessedForUpdate`() {
        runBlocking {
            // given
            val errorRecording = createRecordingEntity(filePath = "/recordings/error.mp4")
            val saved = repository.save(errorRecording)
            repository.markProcessedWithError(saved.id!!, Instant.now(), "Corrupted video")

            val normalRecording = createRecordingEntity(filePath = "/recordings/normal.mp4")
            repository.save(normalRecording)

            // when
            val result =
                repository.findUnprocessedForUpdate(
                    stuckBefore = Instant.now().minusSeconds(3600),
                    createdBefore = Instant.now(),
                    limit = 10,
                )

            // then
            assertEquals(1, result.size)
            assertEquals("/recordings/normal.mp4", result[0].filePath)
        }
    }

    // endregion

    // region Counters

    @Test
    fun `should count all recordings`() {
        runBlocking {
            // given
            repeat(3) { i ->
                repository.save(createRecordingEntity(filePath = "/recordings/file$i.mp4"))
            }

            // when
            val count = repository.countAll()

            // then
            assertEquals(3L, count)
        }
    }

    @Test
    fun `should count processed recordings`() {
        runBlocking {
            // given
            repository.save(createRecordingEntity(filePath = "/recordings/unprocessed.mp4"))
            repository.save(
                createRecordingEntity(
                    filePath = "/recordings/processed1.mp4",
                    processTimestamp = Instant.now(),
                ),
            )
            repository.save(
                createRecordingEntity(
                    filePath = "/recordings/processed2.mp4",
                    processTimestamp = Instant.now(),
                ),
            )

            // when
            val count = repository.countProcessed()

            // then
            assertEquals(2L, count)
        }
    }

    @Test
    fun `should count unprocessed recordings`() {
        runBlocking {
            // given
            repository.save(createRecordingEntity(filePath = "/recordings/unprocessed1.mp4"))
            repository.save(createRecordingEntity(filePath = "/recordings/unprocessed2.mp4"))
            repository.save(
                createRecordingEntity(
                    filePath = "/recordings/processed.mp4",
                    processTimestamp = Instant.now(),
                ),
            )

            // when
            val count = repository.countUnprocessed()

            // then
            assertEquals(2L, count)
        }
    }

    // endregion

    // region Statistics

    @Test
    fun `should return statistics by cameras`() {
        runBlocking {
            // given
            // cam1: 2 recordings, 1 processed, 5 detections
            repository.save(
                createRecordingEntity(
                    filePath = "/recordings/cam1_processed.mp4",
                    camId = "cam1",
                    processTimestamp = Instant.now(),
                    detectionsCount = 5,
                ),
            )
            repository.save(
                createRecordingEntity(
                    filePath = "/recordings/cam1_unprocessed.mp4",
                    camId = "cam1",
                ),
            )
            // cam2: 1 recording, 1 processed, 3 detections
            repository.save(
                createRecordingEntity(
                    filePath = "/recordings/cam2_processed.mp4",
                    camId = "cam2",
                    processTimestamp = Instant.now(),
                    detectionsCount = 3,
                ),
            )

            // when
            val stats = repository.getStatisticsByCameras()

            // then
            assertEquals(2, stats.size)

            val cam1Stats = stats.find { it.camId == "cam1" }
            assertNotNull(cam1Stats)
            assertEquals(2L, cam1Stats!!.recordingsCount)
            assertEquals(1L, cam1Stats.recordingsProcessed)
            assertEquals(5L, cam1Stats.detectionsCount)

            val cam2Stats = stats.find { it.camId == "cam2" }
            assertNotNull(cam2Stats)
            assertEquals(1L, cam2Stats!!.recordingsCount)
            assertEquals(1L, cam2Stats.recordingsProcessed)
            assertEquals(3L, cam2Stats.detectionsCount)
        }
    }

    @Test
    fun `should return empty list when no recordings with camId`() {
        runBlocking {
            // given
            repository.save(createRecordingEntity(filePath = "/recordings/no_cam.mp4", camId = null))

            // when
            val stats = repository.getStatisticsByCameras()

            // then
            assertTrue(stats.isEmpty())
        }
    }

    @Test
    fun `should order statistics by camId`() {
        runBlocking {
            // given
            repository.save(createRecordingEntity(filePath = "/recordings/c.mp4", camId = "cam_c"))
            repository.save(createRecordingEntity(filePath = "/recordings/a.mp4", camId = "cam_a"))
            repository.save(createRecordingEntity(filePath = "/recordings/b.mp4", camId = "cam_b"))

            // when
            val stats = repository.getStatisticsByCameras()

            // then
            assertEquals(3, stats.size)
            assertEquals("cam_a", stats[0].camId)
            assertEquals("cam_b", stats[1].camId)
            assertEquals("cam_c", stats[2].camId)
        }
    }

    // endregion

    // region Processing Rate

    @Test
    fun `should return zero processing rate when no recent processed recordings`() {
        runBlocking {
            // given - no recordings in the 5-10 minute window

            // when
            val rate = repository.getProcessingRatePerMinuteLast5Minutes()

            // then
            assertEquals(0.0, rate)
        }
    }

    // endregion
}
