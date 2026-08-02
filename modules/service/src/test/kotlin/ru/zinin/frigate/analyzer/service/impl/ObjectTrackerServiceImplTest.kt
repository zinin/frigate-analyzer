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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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

    /** The deployment shape this feature exists for: TTL long enough to mute static objects. */
    private val longTtlProps =
        ObjectTrackerProperties(
            ttl = Duration.ofHours(12),
            reappearGap = Duration.ofHours(1),
            // cleanupRetention >= ttl is an existing invariant.
            cleanupRetention = Duration.ofHours(48),
        )
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

    /**
     * Opens an uninterrupted watch window starting at [from]. [ObjectTrackerServiceImpl.markWatched]
     * refuses to call an absence a reappearance unless the tracker was already watching when that
     * absence began, so a freshly constructed service reports nothing until this has been called.
     * Empty detections suffice: the stamp records that the tracker looked, not what it found. The
     * fixed clock keeps the wall-clock gap to the next call at zero, so the window stays open.
     */
    private suspend fun ObjectTrackerServiceImpl.watchFrom(from: Instant) {
        evaluate(rec(from), emptyList())
    }

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
            coVerify(exactly = 0) { repo.findActive(any(), any(), any()) }
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
            coEvery { repo.findActive(any(), any(), any()) } returns emptyList()
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
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

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
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)
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
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)
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
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existingCar, stalePerson)

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
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)
            coEvery { repo.updateOnMatch(any(), any(), any(), any(), any(), any(), any()) } returns 0L

            assertFailsWith<IllegalStateException> {
                service.evaluate(
                    rec(),
                    listOf(det("car", 0.01f, 0.0f, 0.51f, 0.5f)),
                )
            }
        }

    @Test
    fun `findActive uses recordingTimestamp plus or minus TTL as window`() =
        runTest {
            val capturedMin = slot<Instant>()
            val capturedMax = slot<Instant>()
            coEvery { repo.findActive(eq(camId), capture(capturedMin), capture(capturedMax)) } returns emptyList()
            coEvery { uuid.generateV1() } returns UUID.randomUUID()

            service.evaluate(rec(), listOf(det("car", 0f, 0f, 0.5f, 0.5f)))

            assertEquals(Instant.parse("2026-04-27T11:30:00Z"), capturedMin.captured)
            assertEquals(Instant.parse("2026-04-27T12:30:00Z"), capturedMax.captured)
        }

    @Test
    fun `out-of-order older recording matches existing track via updateOnMatch with original lastSeenAt`() =
        runTest {
            val newer = Instant.parse("2026-04-27T12:01:00Z")
            val existing = track("car", 0f, 0f, 0.5f, 0.5f, lastSeen = newer)
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

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
    fun `match after a long absence is reported as reappeared`() =
        runTest {
            // Long ttl keeps the track "active" all day; reappear-gap is what separates an object
            // that left and came back from one that never moved.
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, longTtlProps, transactionalOperator)
            val absentSince = fixedNow.minus(Duration.ofHours(8))
            svc.watchFrom(absentSince)
            val existing = track("person", 0f, 0f, 0.5f, 0.5f, lastSeen = absentSince)
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("person", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(0, delta.newTracksCount)
            assertEquals(1, delta.matchedTracksCount)
            assertEquals(1, delta.reappearedTracksCount)
            assertEquals(listOf("person"), delta.reappearedClasses)
            // Reuses the existing row rather than growing the table.
            coVerify(exactly = 1) { repo.updateOnMatch(existing.id!!, any(), any(), any(), any(), any(), recId) }
            coVerify(exactly = 0) { repo.save(any()) }
        }

    @Test
    fun `continuously detected static object never counts as reappeared`() =
        runTest {
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, longTtlProps, transactionalOperator)
            // A parked car seen 40s ago: the recording cadence keeps its gap far below reappearGap.
            val existing = track("car", 0f, 0f, 0.5f, 0.5f, lastSeen = fixedNow.minusSeconds(40))
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("car", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.matchedTracksCount)
            assertEquals(0, delta.reappearedTracksCount)
            assertTrue(delta.reappearedClasses.isEmpty())
        }

    @Test
    fun `out-of-order older recording never counts as reappeared`() =
        runTest {
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, longTtlProps, transactionalOperator)
            // Track already advanced past this recording by a later one: absence is negative.
            val existing = track("person", 0f, 0f, 0.5f, 0.5f, lastSeen = fixedNow.plus(Duration.ofHours(3)))
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("person", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.matchedTracksCount)
            assertEquals(0, delta.reappearedTracksCount)
        }

    @Test
    fun `default reappearGap equal to ttl keeps the old suppress-everything behaviour`() =
        runTest {
            // Default props: reappearGap == ttl == 30m, and findActive never returns a track older
            // than ttl, so no match can ever reach the gap.
            service.watchFrom(fixedNow.minus(props.ttl))
            val existing = track("person", 0f, 0f, 0.5f, 0.5f, lastSeen = fixedNow.minus(props.ttl).plusSeconds(1))
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = service.evaluate(rec(), listOf(det("person", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.matchedTracksCount)
            assertEquals(0, delta.reappearedTracksCount)
        }

    @Test
    fun `absence of exactly ttl under the default gap is still not a reappearance`() =
        runTest {
            // The boundary the test above steps around with plusSeconds(1). findActive's lower bound
            // is inclusive, so a track last seen exactly ttl ago is returned and its absence is
            // exactly ttl — the largest value reachable at all. Only a strict comparison keeps the
            // documented default a real no-op.
            service.watchFrom(fixedNow.minus(props.ttl))
            val existing = track("person", 0f, 0f, 0.5f, 0.5f, lastSeen = fixedNow.minus(props.ttl))
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = service.evaluate(rec(), listOf(det("person", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.matchedTracksCount)
            assertEquals(0, delta.reappearedTracksCount)
        }

    @Test
    fun `absence of exactly reappearGap is not a reappearance`() =
        runTest {
            // Pins the same strictness for an enabled configuration, so a switch back to >= cannot
            // slip through on the tuned deployment shape either.
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, longTtlProps, transactionalOperator)
            svc.watchFrom(fixedNow.minus(longTtlProps.reappearGap))
            val existing =
                track("person", 0f, 0f, 0.5f, 0.5f, lastSeen = fixedNow.minus(longTtlProps.reappearGap))
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("person", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.matchedTracksCount)
            assertEquals(0, delta.reappearedTracksCount)
        }

    @Test
    fun `two tracks of one class reappearing stay inside matched and repeat the class`() =
        runTest {
            // Both contracts DetectionDelta documents but nothing pinned: reappeared is a subset of
            // matched, and reappearedClasses repeats a class once per track.
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, longTtlProps, transactionalOperator)
            val absentSince = fixedNow.minus(Duration.ofHours(8))
            svc.watchFrom(absentSince)
            val left = track("person", 0f, 0f, 0.2f, 0.2f, lastSeen = absentSince)
            val right = track("person", 0.6f, 0.6f, 0.9f, 0.9f, lastSeen = absentSince)
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(left, right)

            val delta =
                svc.evaluate(
                    rec(),
                    listOf(
                        det("person", 0.01f, 0.01f, 0.21f, 0.2f),
                        det("person", 0.61f, 0.61f, 0.91f, 0.9f),
                    ),
                )

            assertEquals(2, delta.matchedTracksCount)
            assertEquals(2, delta.reappearedTracksCount)
            assertTrue(delta.reappearedTracksCount <= delta.matchedTracksCount)
            assertEquals(listOf("person", "person"), delta.reappearedClasses)
        }

    @Test
    fun `first evaluation after an interruption does not report a reappearance`() =
        runTest {
            // Nothing was watching while this absence accumulated. The unprocessed queue is drained
            // newest-first, so the first recording after a restart or a stalled pipeline carries the
            // whole interruption as an apparent absence for every static object still matching.
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, longTtlProps, transactionalOperator)
            val existing = track("car", 0f, 0f, 0.5f, 0.5f, lastSeen = fixedNow.minus(Duration.ofHours(8)))
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("car", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.matchedTracksCount)
            assertEquals(0, delta.reappearedTracksCount)
            assertTrue(delta.reappearedClasses.isEmpty())
        }

    @Test
    fun `a track missed on the first pass after an interruption still does not reappear`() =
        runTest {
            // Why the guard is per track rather than per recording. A static object the detector
            // happens to miss in the first frame after an interruption keeps its old lastSeenAt, so
            // a per-recording guard would let it through on the very next recording — and detector
            // misses are common enough to make that the normal case, not an edge one.
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, longTtlProps, transactionalOperator)
            val existing = track("car", 0f, 0f, 0.5f, 0.5f, lastSeen = fixedNow.minus(Duration.ofHours(3)))
            // First pass after the interruption detects nothing, so the track is not advanced.
            svc.evaluate(rec(), emptyList())
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta =
                svc.evaluate(rec(fixedNow.plusSeconds(10)), listOf(det("car", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.matchedTracksCount)
            assertEquals(0, delta.reappearedTracksCount)
        }

    @Test
    fun `a wall-clock gap between evaluations closes the watch window`() =
        runTest {
            // The interruption is only visible on the wall clock: recording timestamps say nothing
            // about whether this process was running between them. Same data as `match after a long
            // absence is reported as reappeared` — only the processing gap differs.
            val wallClock = SteppingClock(fixedNow)
            val svc = ObjectTrackerServiceImpl(repo, uuid, wallClock, longTtlProps, transactionalOperator)
            val absentSince = fixedNow.minus(Duration.ofHours(8))
            svc.watchFrom(absentSince)
            wallClock.advance(longTtlProps.reappearGap.plusMinutes(1))
            val existing = track("person", 0f, 0f, 0.5f, 0.5f, lastSeen = absentSince)
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("person", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.matchedTracksCount)
            assertEquals(0, delta.reappearedTracksCount)
        }

    /** Wall clock the test moves by hand; [Clock.fixed] cannot express a processing gap. */
    private class SteppingClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = SteppingClock(current, zone)

        override fun instant(): Instant = current

        fun advance(by: Duration) {
            current = current.plus(by)
        }
    }

    @Test
    fun `reappearGap above ttl is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            ObjectTrackerProperties(ttl = Duration.ofMinutes(30), reappearGap = Duration.ofHours(1))
        }
    }

    @Test
    fun `non-positive reappearGap is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            ObjectTrackerProperties(reappearGap = Duration.ZERO)
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
