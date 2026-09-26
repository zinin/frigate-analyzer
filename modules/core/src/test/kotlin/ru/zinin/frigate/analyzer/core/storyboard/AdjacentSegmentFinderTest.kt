package ru.zinin.frigate.analyzer.core.storyboard

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.video.VideoInfo
import ru.zinin.frigate.analyzer.core.video.VideoProbe
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AdjacentSegmentFinderTest {
    @TempDir
    lateinit var dir: Path

    private val service = mockk<RecordingEntityService>()
    private val probe = mockk<VideoProbe>()
    private val base = Instant.parse("2026-09-23T10:00:00Z")

    /** Часы идут за виртуальным временем теста: `delay` в поиске двигает и их. */
    private fun TestScope.finder(): AdjacentSegmentFinder {
        val scope = this
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = base.plusMillis(scope.currentTime)
            }
        return AdjacentSegmentFinder(service, probe, clock)
    }

    private fun file(
        name: String,
        modified: Instant = base.minusSeconds(60),
    ): Path =
        dir.resolve(name).also {
            Files.write(it, byteArrayOf(1))
            Files.setLastModifiedTime(it, FileTime.from(modified))
        }

    private fun recording(
        start: Instant,
        file: Path,
    ) = RecordingDto(
        id = UUID.randomUUID(),
        creationTimestamp = start,
        filePath = file.toString(),
        fileCreationTimestamp = start,
        camId = "cam1",
        recordDate = LocalDate.of(2026, 9, 23),
        recordTime = LocalTime.of(10, 0),
        recordTimestamp = start,
        startProcessingTimestamp = null,
        processTimestamp = null,
        processAttempts = 0,
        detectionsCount = 0,
        analyzeTime = 0,
        analyzedFramesCount = 0,
        errorMessage = null,
    )

    private fun info(duration: Double) = VideoInfo(durationSeconds = duration, width = 2880, height = 1620, fps = 20.0, hasAudio = false)

    private fun current() = recording(base, file("current.mp4"))

    @Test
    fun `a previous segment that ends where the current one starts is used`() =
        runTest {
            val previous = recording(base.minusSeconds(10), file("previous.mp4"))
            coEvery { service.findPreviousSegment("cam1", base.minusSeconds(60), base) } returns previous
            coEvery { probe.probe(Path.of(previous.filePath)) } returns info(10.0)

            val segment = assertNotNull(finder().previous(current()))

            assertEquals(previous, segment.recording)
            assertEquals(10.0, segment.info.durationSeconds)
        }

    @Test
    fun `a previous segment followed by a gap is not used`() =
        runTest {
            val previous = recording(base.minusSeconds(10), file("previous.mp4"))
            coEvery { service.findPreviousSegment(any(), any(), any()) } returns previous
            coEvery { probe.probe(any()) } returns info(7.0)

            assertNull(finder().previous(current()))
        }

    @Test
    fun `a failed lookup of the previous segment counts as no segment`() =
        runTest {
            coEvery { service.findPreviousSegment(any(), any(), any()) } throws RuntimeException("db down")

            assertNull(finder().previous(current()))
        }

    @Test
    fun `a failed lookup of the next segment is retried until the deadline`() =
        runTest {
            val next = recording(base.plusSeconds(10), file("next.mp4"))
            coEvery { service.findNextSegment(any(), any(), any()) } throws RuntimeException("db down") andThen next
            coEvery { probe.probe(any()) } returns info(10.0)

            assertNotNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(1_000L, currentTime)
        }

    @Test
    fun `a next segment already in the database is taken on the first lookup`() =
        runTest {
            val next = recording(base.plusSeconds(10), file("next.mp4"))
            coEvery { service.findNextSegment("cam1", base, base.plusSeconds(15)) } returns next
            coEvery { probe.probe(Path.of(next.filePath)) } returns info(10.0)

            val segment = assertNotNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(next, segment.recording)
            assertEquals(0L, currentTime)
        }

    @Test
    fun `a next segment that appears on the third lookup is waited for`() =
        runTest {
            val next = recording(base.plusSeconds(10), file("next.mp4"))
            var calls = 0
            coEvery { service.findNextSegment(any(), any(), any()) } answers { if (++calls < 3) null else next }
            coEvery { probe.probe(any()) } returns info(10.0)

            assertNotNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(3, calls)
            assertEquals(2_000L, currentTime)
        }

    @Test
    fun `a next segment that never appears is given up at the deadline`() =
        runTest {
            coEvery { service.findNextSegment(any(), any(), any()) } returns null

            assertNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(30_000L, currentTime)
            coVerify(exactly = 31) { service.findNextSegment(any(), any(), any()) }
        }

    @Test
    fun `a deadline in the past means exactly one lookup and no waiting`() =
        runTest {
            coEvery { service.findNextSegment(any(), any(), any()) } returns null

            assertNull(finder().next(current(), 10.0, deadline = base.minusSeconds(3600)))

            assertEquals(0L, currentTime)
            coVerify(exactly = 1) { service.findNextSegment(any(), any(), any()) }
        }

    @Test
    fun `a next segment that starts after a gap is not used and not waited for`() =
        runTest {
            val next = recording(base.plusSeconds(13), file("next.mp4"))
            coEvery { service.findNextSegment(any(), any(), any()) } returns next

            assertNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(0L, currentTime)
            coVerify(exactly = 0) { probe.probe(any()) }
        }

    @Test
    fun `a next segment still being written is probed only once it settles`() =
        runTest {
            val next = recording(base.plusSeconds(10), file("next.mp4", modified = base))
            coEvery { service.findNextSegment(any(), any(), any()) } returns next
            coEvery { probe.probe(any()) } returns info(10.0)

            assertNotNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(1_000L, currentTime)
            coVerify(exactly = 1) { probe.probe(any()) }
        }

    @Test
    fun `a next segment ffprobe cannot read yet is retried`() =
        runTest {
            val next = recording(base.plusSeconds(10), file("next.mp4"))
            coEvery { service.findNextSegment(any(), any(), any()) } returns next
            coEvery { probe.probe(any()) } throws RuntimeException("moov atom not found") andThen info(10.0)

            assertNotNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(1_000L, currentTime)
        }
}
