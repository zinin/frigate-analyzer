package ru.zinin.frigate.analyzer.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.reactive.TransactionCallback
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.persistent.ObjectTrackEntity
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import ru.zinin.frigate.analyzer.service.repository.ObjectTrackRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObjectTrackerServiceImplTest {
    private val repo =
        mockk<ObjectTrackRepository>(relaxed = true) {
            coEvery { updateOnMatch(any(), any(), any(), any(), any(), any(), any()) } returns 1L
        }
    private val uuid = mockk<UUIDGeneratorHelper>()
    private val fixedNow = Instant.parse("2026-04-27T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val props = ObjectTrackerProperties()
    private val transactionalOperator =
        mockk<TransactionalOperator> {
            every { execute(any<TransactionCallback<Any>>()) } answers {
                val cb = firstArg<TransactionCallback<Any>>()
                val rawResult = cb.doInTransaction(mockk<ReactiveTransaction>(relaxed = true))
                // executeAndAwait wraps the suspend lambda inside mono(Unconfined);
                // we convert the Mono to Flux without blocking so the Unconfined
                // dispatcher can complete inside runTest's virtual time loop.
                if (rawResult is Mono<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (rawResult as Mono<Any>).flux()
                } else {
                    @Suppress("UNCHECKED_CAST")
                    Flux.just(rawResult)
                }
            }
        }
    private val service: ObjectTrackerServiceImpl =
        ObjectTrackerServiceImpl(repo, uuid, clock, props, transactionalOperator)

    private val camId = "front"
    private val recId = UUID.randomUUID()

    private fun rec(t: Instant = fixedNow): RecordingDto =
        RecordingDto(
            id = recId,
            creationTimestamp = t,
            filePath = "/r.mp4",
            fileCreationTimestamp = t,
            camId = camId,
            recordDate = LocalDate.from(t.atZone(ZoneOffset.UTC)),
            recordTime = LocalTime.from(t.atZone(ZoneOffset.UTC)),
            recordTimestamp = t,
            startProcessingTimestamp = t,
            processTimestamp = t,
            processAttempts = 1,
            detectionsCount = 1,
            analyzeTime = 1,
            analyzedFramesCount = 1,
            errorMessage = null,
        )

    private fun det(
        className: String,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        conf: Float = 0.9f,
    ) = DetectionEntity(
        id = UUID.randomUUID(),
        creationTimestamp = fixedNow,
        recordingId = recId,
        detectionTimestamp = fixedNow,
        frameIndex = 0,
        model = "yolo",
        classId = 0,
        className = className,
        confidence = conf,
        x1 = x1,
        y1 = y1,
        x2 = x2,
        y2 = y2,
    )

    private fun track(
        className: String,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        lastSeen: Instant = fixedNow,
    ) = ObjectTrackEntity(
        id = UUID.randomUUID(),
        creationTimestamp = lastSeen,
        camId = camId,
        className = className,
        bboxX1 = x1,
        bboxY1 = y1,
        bboxX2 = x2,
        bboxY2 = y2,
        lastSeenAt = lastSeen,
        lastRecordingId = null,
    )

    @Test
    fun `empty detections produce zero delta and no DB writes and no transaction`() =
        runTest {
            val delta = service.evaluate(rec(), emptyList())

            assertEquals(0, delta.newTracksCount)
            assertEquals(0, delta.matchedTracksCount)
            coVerify(exactly = 0) { repo.findActive(any(), any()) }
            coVerify(exactly = 0) { repo.save(any()) }
            coVerify(exactly = 0) { repo.updateOnMatch(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { transactionalOperator.execute<Any>(any()) }
        }

    @Test
    fun `low confidence detections below floor produce zero delta and no writes`() =
        runTest {
            val delta =
                service.evaluate(
                    rec(),
                    listOf(det("car", 0f, 0f, 0.5f, 0.5f, conf = 0.2f)),
                )

            assertEquals(0, delta.newTracksCount)
            assertEquals(0, delta.matchedTracksCount)
            coVerify(exactly = 0) { repo.save(any()) }
            coVerify(exactly = 0) { repo.updateOnMatch(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `first time appearance creates a new track and reports new=1`() =
        runTest {
            coEvery { repo.findActive(any(), any()) } returns emptyList()
            coEvery { uuid.generateV1() } returns UUID.randomUUID()

            val delta =
                service.evaluate(
                    rec(),
                    listOf(det("car", 0f, 0f, 0.5f, 0.5f)),
                )

            assertEquals(1, delta.newTracksCount)
            assertEquals(0, delta.matchedTracksCount)
            assertTrue(delta.newClasses.contains("car"))
            coVerify(exactly = 1) { repo.save(any()) }
        }

    @Test
    fun `match against existing active track reports matched=1 and updates`() =
        runTest {
            val existing = track("car", 0f, 0f, 0.5f, 0.5f)
            coEvery { repo.findActive(any(), any()) } returns listOf(existing)

            val delta =
                service.evaluate(
                    rec(),
                    listOf(det("car", 0.01f, 0.0f, 0.51f, 0.5f)),
                )

            assertEquals(0, delta.newTracksCount)
            assertEquals(1, delta.matchedTracksCount)
            coVerify(exactly = 1) { repo.updateOnMatch(existing.id!!, any(), any(), any(), any(), any(), recId) }
            coVerify(exactly = 0) { repo.save(any()) }
        }

    @Test
    fun `same class but distant bbox treated as new track`() =
        runTest {
            val existing = track("car", 0f, 0f, 0.2f, 0.2f)
            coEvery { repo.findActive(any(), any()) } returns listOf(existing)
            coEvery { uuid.generateV1() } returns UUID.randomUUID()

            val delta =
                service.evaluate(
                    rec(),
                    listOf(det("car", 0.7f, 0.7f, 0.9f, 0.9f)),
                )

            assertEquals(1, delta.newTracksCount)
            assertEquals(0, delta.matchedTracksCount)
        }

    @Test
    fun `mixed scenario car matches person is new`() =
        runTest {
            val existing = track("car", 0f, 0f, 0.5f, 0.5f)
            coEvery { repo.findActive(any(), any()) } returns listOf(existing)
            coEvery { uuid.generateV1() } returns UUID.randomUUID()

            val delta =
                service.evaluate(
                    rec(),
                    listOf(
                        det("car", 0.01f, 0.0f, 0.51f, 0.5f),
                        det("person", 0.6f, 0.6f, 0.8f, 0.9f),
                    ),
                )

            assertEquals(1, delta.newTracksCount)
            assertEquals(1, delta.matchedTracksCount)
            assertEquals(listOf("person"), delta.newClasses)
        }

    @Test
    fun `unmatched active tracks are reported as stale`() =
        runTest {
            val existingCar = track("car", 0f, 0f, 0.5f, 0.5f)
            val stalePerson = track("person", 0.6f, 0.6f, 0.8f, 0.9f)
            coEvery { repo.findActive(any(), any()) } returns listOf(existingCar, stalePerson)

            val delta =
                service.evaluate(
                    rec(),
                    listOf(det("car", 0.01f, 0.0f, 0.51f, 0.5f)),
                )

            assertEquals(0, delta.newTracksCount)
            assertEquals(1, delta.matchedTracksCount)
            assertEquals(1, delta.staleTracksCount)
        }

    @Test
    fun `missing row during match update fails instead of silently suppressing`() =
        runTest {
            val existing = track("car", 0f, 0f, 0.5f, 0.5f)
            coEvery { repo.findActive(any(), any()) } returns listOf(existing)
            coEvery { repo.updateOnMatch(any(), any(), any(), any(), any(), any(), any()) } returns 0L

            assertFailsWith<IllegalStateException> {
                service.evaluate(
                    rec(),
                    listOf(det("car", 0.01f, 0.0f, 0.51f, 0.5f)),
                )
            }
        }

    @Test
    fun `findActive uses recordingTimestamp minus TTL as threshold`() =
        runTest {
            val captured = slot<Instant>()
            coEvery { repo.findActive(eq(camId), capture(captured)) } returns emptyList()
            coEvery { uuid.generateV1() } returns UUID.randomUUID()

            service.evaluate(rec(), listOf(det("car", 0f, 0f, 0.5f, 0.5f)))

            assertEquals(Instant.parse("2026-04-27T11:58:00Z"), captured.captured)
        }

    @Test
    fun `out-of-order older recording matches existing track via updateOnMatch with original lastSeenAt`() =
        runTest {
            val newer = Instant.parse("2026-04-27T12:01:00Z")
            val existing = track("car", 0f, 0f, 0.5f, 0.5f, lastSeen = newer)
            coEvery { repo.findActive(any(), any()) } returns listOf(existing)

            val delta =
                service.evaluate(
                    rec(),
                    listOf(det("car", 0.01f, 0.0f, 0.51f, 0.5f)),
                )

            assertEquals(0, delta.newTracksCount)
            assertEquals(1, delta.matchedTracksCount)
            coVerify(exactly = 1) {
                repo.updateOnMatch(
                    existing.id!!,
                    any(),
                    any(),
                    any(),
                    any(),
                    Instant.parse("2026-04-27T12:00:00Z"),
                    recId,
                )
            }
        }

    @Test
    fun `cleanupExpired delegates to repo with now-minus-retention`() =
        runTest {
            coEvery { repo.deleteExpired(any()) } returns 7L

            val deleted = service.cleanupExpired()

            assertEquals(7L, deleted)
            coVerify { repo.deleteExpired(Instant.parse("2026-04-27T11:00:00Z")) }
        }
}
