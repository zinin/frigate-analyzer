package ru.zinin.frigate.analyzer.core.storyboard

import io.mockk.MockKMatcherScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.core.config.properties.StoryboardProperties
import ru.zinin.frigate.analyzer.core.video.VideoInfo
import ru.zinin.frigate.analyzer.core.video.VideoProbe
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.response.BBox
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.Detection
import ru.zinin.frigate.analyzer.model.response.ImageSize
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoryboardBuilderTest {
    private val probe = mockk<VideoProbe>()
    private val finder = mockk<AdjacentSegmentFinder>()
    private val sampler = mockk<StoryboardFrameSampler>()
    private val now = Instant.parse("2026-09-23T10:01:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val recordStart = Instant.parse("2026-09-23T10:00:00Z")
    private val currentPath = Path.of("/rec/cam1/00.00.mp4")
    private val previousPath = Path.of("/rec/cam1/59.50.mp4")
    private val nextPath = Path.of("/rec/cam1/00.12.mp4")
    private val tileJpeg: ByteArray =
        BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB)
            .also { image ->
                val graphics = image.createGraphics()
                graphics.color = Color.GRAY
                graphics.fillRect(0, 0, 64, 36)
                graphics.dispose()
            }.let { image -> ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray() }

    init {
        coEvery { probe.probe(currentPath) } returns info(12.0)
        coEvery { sampler.sample(any(), any(), any(), any(), any()) } answers { List(arg<Int>(3)) { tileJpeg } }
    }

    private fun builder(properties: StoryboardProperties = StoryboardProperties()) =
        StoryboardBuilder(properties, probe, finder, sampler, StoryboardComposer(), clock)

    private fun info(duration: Double) = VideoInfo(durationSeconds = duration, width = 2880, height = 1620, fps = 20.0, hasAudio = false)

    private fun recording(
        path: Path = currentPath,
        start: Instant = recordStart,
        fileCreated: Instant = recordStart.plusSeconds(12),
    ) = RecordingDto(
        id = UUID.randomUUID(),
        creationTimestamp = fileCreated,
        filePath = path.toString(),
        fileCreationTimestamp = fileCreated,
        camId = "cam1",
        recordDate = LocalDate.of(2026, 9, 23),
        recordTime = LocalTime.of(10, 0),
        recordTimestamp = start,
        startProcessingTimestamp = null,
        processTimestamp = null,
        processAttempts = 0,
        detectionsCount = 1,
        analyzeTime = 0,
        analyzedFramesCount = 5,
        errorMessage = null,
    )

    private fun segment(
        path: Path,
        start: Instant,
        duration: Double,
    ) = Segment(recording(path = path, start = start), path, info(duration))

    private fun detection(
        className: String,
        confidence: Double,
    ) = Detection(2, className, confidence, BBox(0.0, 0.0, 1.0, 1.0))

    private fun frame(
        index: Int,
        offset: Double?,
        detections: List<Detection> = listOf(detection("car", 0.9)),
    ) = FrameData(
        recordId = UUID.randomUUID(),
        frameIndex = index,
        frameBytes = ByteArray(0),
        detectResponse = DetectResponse(detections, 0, ImageSize(2880, 1620), "m"),
        offsetSeconds = offset,
    )

    private fun MockKMatcherScope.near(value: Double) = match<Double> { abs(it - value) < 1e-9 }

    @Test
    fun `a detection in the middle stays inside the recording`() =
        runTest {
            val built = assertNotNull(builder().build(recording(), listOf(frame(2, 6.0))))

            assertEquals(1.0, built.zeroSeconds)
            val storyboard = built.storyboard
            assertEquals(16, storyboard.tiles)
            assertEquals(0.66, storyboard.stepSeconds, 1e-9)
            assertEquals(10.0, storyboard.durationSeconds, 1e-9)
            assertEquals(listOf(DescriptionRequest.DetectionMark("car", 0.9, 5.0)), storyboard.detections)
            assertFalse(storyboard.missingBefore)
            assertFalse(storyboard.missingAfter)
            assertNotNull(ImageIO.read(ByteArrayInputStream(storyboard.image)))
            coVerify(exactly = 1) { sampler.sample(currentPath, near(1.0), near(0.66), 16, 640) }
            coVerify(exactly = 0) { finder.previous(any()) }
            coVerify(exactly = 0) { finder.next(any(), any(), any()) }
        }

    @Test
    fun `a detection near the start takes the tail of the previous segment`() =
        runTest {
            coEvery { finder.previous(any()) } returns segment(previousPath, recordStart.minusSeconds(10), 10.0)

            val built = assertNotNull(builder().build(recording(), listOf(frame(0, 1.0))))

            assertEquals(-4.0, built.zeroSeconds)
            assertEquals(listOf(DescriptionRequest.DetectionMark("car", 0.9, 5.0)), built.storyboard.detections)
            assertFalse(built.storyboard.missingBefore)
            coVerify(exactly = 1) { sampler.sample(previousPath, near(6.0), near(0.66), 7, 640) }
            coVerify(exactly = 1) { sampler.sample(currentPath, near(0.62), near(0.66), 9, 640) }
        }

    /** Предыдущего сегмента нет: отрезок начинается с начала записи — 0.0..6.0 с, клетки через 0.5 с. */
    @Test
    fun `a missing previous segment starts the footage at the recording`() =
        runTest {
            coEvery { finder.previous(any()) } returns null

            val built = assertNotNull(builder().build(recording(), listOf(frame(0, 1.0))))

            assertTrue(built.storyboard.missingBefore)
            assertEquals(0.0, built.zeroSeconds)
            assertEquals(6.0, built.storyboard.durationSeconds, 1e-9)
            assertEquals(12, built.storyboard.tiles)
            coVerify(exactly = 1) { sampler.sample(currentPath, near(0.0), near(0.5), 12, 640) }
        }

    @Test
    fun `a detection near the end takes the head of the next segment`() =
        runTest {
            coEvery { finder.next(any(), any(), any()) } returns segment(nextPath, recordStart.plusSeconds(12), 10.0)

            val built = assertNotNull(builder().build(recording(), listOf(frame(4, 10.0))))

            assertFalse(built.storyboard.missingAfter)
            assertEquals(10.0, built.storyboard.durationSeconds, 1e-9)
            coVerify(exactly = 1) { sampler.sample(currentPath, near(5.0), near(0.66), 11, 640) }
            coVerify(exactly = 1) { sampler.sample(nextPath, near(0.26), near(0.66), 5, 640) }
        }

    @Test
    fun `a next segment that never came leaves the end of the footage open`() =
        runTest {
            coEvery { finder.next(any(), any(), any()) } returns null

            val built = assertNotNull(builder().build(recording(), listOf(frame(4, 10.0))))

            assertTrue(built.storyboard.missingAfter)
            assertEquals(7.0, built.storyboard.durationSeconds, 1e-9)
            assertEquals(14, built.storyboard.tiles)
            coVerify(exactly = 0) { sampler.sample(nextPath, any(), any(), any(), any()) }
        }

    @Test
    fun `the builder hands the finder its deadline`() =
        runTest {
            val deadline = slot<Instant>()
            coEvery { finder.next(any(), any(), capture(deadline)) } returns null

            builder().build(recording(fileCreated = Instant.parse("2026-09-23T10:00:12Z")), listOf(frame(4, 10.0)))

            assertEquals(Instant.parse("2026-09-23T10:00:54Z"), deadline.captured)
        }

    /** Файл появился в 10:00:12, следующий ожидался в 10:00:24, задача стартовала в 10:01:00. */
    @Test
    fun `the next-segment deadline counts from the expected appearance once that has passed`() {
        val deadline = builder().nextDeadline(recording(fileCreated = Instant.parse("2026-09-23T10:00:12Z")), 12.0, now)

        assertEquals(Instant.parse("2026-09-23T10:00:54Z"), deadline)
    }

    @Test
    fun `the next-segment deadline counts from the start of the job when that comes first`() {
        val deadline = builder().nextDeadline(recording(fileCreated = now.minusSeconds(2)), 12.0, now)

        assertEquals(now.plusSeconds(30), deadline)
    }

    @Test
    fun `the next-segment deadline of a backlog recording is already past`() {
        val deadline = builder().nextDeadline(recording(fileCreated = now.minus(Duration.ofHours(3))), 12.0, now)

        assertTrue(deadline.isBefore(now))
    }

    @Test
    fun `frames without times cover the whole recording`() =
        runTest {
            val built = assertNotNull(builder().build(recording(), listOf(frame(1, null))))

            assertEquals(0.0, built.zeroSeconds)
            assertEquals(12.0, built.storyboard.durationSeconds, 1e-9)
            assertEquals(listOf(DescriptionRequest.DetectionMark("car", 0.9, null)), built.storyboard.detections)
            coVerify(exactly = 0) { finder.previous(any()) }
            coVerify(exactly = 0) { finder.next(any(), any(), any()) }
        }

    @Test
    fun `detections are listed per frame and class with the best confidence`() =
        runTest {
            val frame = frame(2, 6.0, detections = listOf(detection("car", 0.6), detection("car", 0.8), detection("person", 0.7)))

            val built = assertNotNull(builder().build(recording(), listOf(frame)))

            assertEquals(
                listOf(DescriptionRequest.DetectionMark("car", 0.8, 5.0), DescriptionRequest.DetectionMark("person", 0.7, 5.0)),
                built.storyboard.detections,
            )
        }

    @Test
    fun `a recording ffprobe cannot read gives no storyboard`() =
        runTest {
            coEvery { probe.probe(currentPath) } throws RuntimeException("no video stream")

            assertNull(builder().build(recording(), listOf(frame(2, 6.0))))
            coVerify(exactly = 0) { sampler.sample(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a failure on the current recording gives no storyboard`() =
        runTest {
            coEvery { sampler.sample(currentPath, any(), any(), any(), any()) } throws RuntimeException("ffmpeg exited with 1")

            assertNull(builder().build(recording(), listOf(frame(2, 6.0))))
        }

    /** Пустой ответ по текущей записи — запасной путь: из 7 клеток предыдущего вышла бы раскадровка без самой детекции. */
    @Test
    fun `a current recording that gives no frames gives no storyboard`() =
        runTest {
            coEvery { finder.previous(any()) } returns segment(previousPath, recordStart.minusSeconds(10), 10.0)
            coEvery { sampler.sample(currentPath, any(), any(), any(), any()) } returns emptyList()

            assertNull(builder().build(recording(), listOf(frame(0, 1.0))))
        }

    /** `after = 0` и детекция в самом начале записи: окно −5.0..0.0 с, все 10 клеток — в предыдущем сегменте. */
    @Test
    fun `a window that ends where the recording starts gives no storyboard`() =
        runTest {
            coEvery { finder.previous(any()) } returns segment(previousPath, recordStart.minusSeconds(10), 10.0)

            assertNull(builder(StoryboardProperties(after = Duration.ZERO)).build(recording(), listOf(frame(0, 0.0))))
            coVerify(exactly = 0) { sampler.sample(any(), any(), any(), any(), any()) }
        }

    /** Запись 0.3 с короче шага клеток, ≈0.67 с: 8 клеток ложатся в предыдущий сегмент, 8 — в следующий, в неё ни одной. */
    @Test
    fun `a recording shorter than the tile step gives no storyboard`() =
        runTest {
            coEvery { probe.probe(currentPath) } returns info(0.3)
            coEvery { finder.previous(any()) } returns segment(previousPath, recordStart.minusSeconds(10), 10.0)
            coEvery { finder.next(any(), any(), any()) } returns segment(nextPath, recordStart.plusMillis(300), 10.0)

            assertNull(builder().build(recording(), listOf(frame(0, 0.0), frame(1, 0.2))))
            coVerify(exactly = 0) { sampler.sample(any(), any(), any(), any(), any()) }
        }

    /**
     * 9 клеток 64×36: столбцов 4, как в плане на 16 — под их ширину ffmpeg уже нарезал клетки, — а рядов 3.
     * Показанный отрезок и его ноль начинаются с текущей записи: 0.0..6.0 с.
     */
    @Test
    fun `a failure on a neighbour leaves only its tiles out`() =
        runTest {
            coEvery { finder.previous(any()) } returns segment(previousPath, recordStart.minusSeconds(10), 10.0)
            coEvery { sampler.sample(previousPath, any(), any(), any(), any()) } throws RuntimeException("ffmpeg exited with 1")

            val built = assertNotNull(builder().build(recording(), listOf(frame(0, 1.0))))

            assertTrue(built.storyboard.missingBefore)
            assertEquals(9, built.storyboard.tiles)
            assertEquals(0.0, built.zeroSeconds)
            assertEquals(6.0, built.storyboard.durationSeconds, 1e-9)
            assertEquals(listOf(DescriptionRequest.DetectionMark("car", 0.9, 1.0)), built.storyboard.detections)
            val grid = assertNotNull(ImageIO.read(ByteArrayInputStream(built.storyboard.image)))
            assertEquals(256, grid.width)
            assertEquals(108, grid.height)
        }

    /** ffmpeg не везде падает, не отдав ни кадра: пустой ответ по соседу — такой же сбой, и ноль тот же, что выше. */
    @Test
    fun `a previous segment that gives no frames counts as a failed one`() =
        runTest {
            coEvery { finder.previous(any()) } returns segment(previousPath, recordStart.minusSeconds(10), 10.0)
            coEvery { sampler.sample(previousPath, any(), any(), any(), any()) } returns emptyList()

            val built = assertNotNull(builder().build(recording(), listOf(frame(0, 1.0))))

            assertTrue(built.storyboard.missingBefore)
            assertEquals(9, built.storyboard.tiles)
            assertEquals(0.0, built.zeroSeconds)
            assertEquals(6.0, built.storyboard.durationSeconds, 1e-9)
        }

    /** Без клеток следующего сегмента показанный отрезок кончается там, где кончается запись: 5.0..12.0 с. */
    @Test
    fun `a failure on the next segment ends the footage with the recording`() =
        runTest {
            coEvery { finder.next(any(), any(), any()) } returns segment(nextPath, recordStart.plusSeconds(12), 10.0)
            coEvery { sampler.sample(nextPath, any(), any(), any(), any()) } throws RuntimeException("ffmpeg exited with 1")

            val built = assertNotNull(builder().build(recording(), listOf(frame(4, 10.0))))

            assertTrue(built.storyboard.missingAfter)
            assertEquals(11, built.storyboard.tiles)
            assertEquals(7.0, built.storyboard.durationSeconds, 1e-9)
        }

    /** Те же числа, что при исключении. Иначе клетки соседа выпали бы молча, а с ними и «Footage after …» из промпта. */
    @Test
    fun `a next segment that gives no frames counts as a failed one`() =
        runTest {
            coEvery { finder.next(any(), any(), any()) } returns segment(nextPath, recordStart.plusSeconds(12), 10.0)
            coEvery { sampler.sample(nextPath, any(), any(), any(), any()) } returns emptyList()

            val built = assertNotNull(builder().build(recording(), listOf(frame(4, 10.0))))

            assertTrue(built.storyboard.missingAfter)
            assertEquals(11, built.storyboard.tiles)
            assertEquals(7.0, built.storyboard.durationSeconds, 1e-9)
        }

    /**
     * Файл кончился раньше, чем обещал ffprobe: 13 клеток из 16 — столбцов по-прежнему 4, рядов тоже 4.
     * Отрезок тогда кончается на последней пришедшей клетке: 1.0..8.92 с.
     */
    @Test
    fun `a recording that ends early gives the tiles that came back`() =
        runTest {
            coEvery { sampler.sample(currentPath, any(), any(), any(), any()) } returns List(13) { tileJpeg }

            val built = assertNotNull(builder().build(recording(), listOf(frame(2, 6.0))))

            assertEquals(13, built.storyboard.tiles)
            assertTrue(built.storyboard.missingAfter)
            assertEquals(7.92, built.storyboard.durationSeconds, 1e-9)
            val grid = assertNotNull(ImageIO.read(ByteArrayInputStream(built.storyboard.image)))
            assertEquals(256, grid.width)
            assertEquals(144, grid.height)
        }

    /** Следующий сегмент отдал 3 клетки из 5: отрезок кончается на последней из них — 5.0..13.58 с. */
    @Test
    fun `a next segment that ends early ends the footage on its last tile`() =
        runTest {
            coEvery { finder.next(any(), any(), any()) } returns segment(nextPath, recordStart.plusSeconds(12), 10.0)
            coEvery { sampler.sample(nextPath, any(), any(), any(), any()) } returns List(3) { tileJpeg }

            val built = assertNotNull(builder().build(recording(), listOf(frame(4, 10.0))))

            assertTrue(built.storyboard.missingAfter)
            assertEquals(14, built.storyboard.tiles)
            assertEquals(8.58, built.storyboard.durationSeconds, 1e-9)
        }

    /** Запись отдала 9 клеток из 11, следующий сегмент упал: отрезок кончается на 10.28 с, а не на конце записи, 12.0 с. */
    @Test
    fun `a recording that ends early before a failed next segment ends the footage on its last tile`() =
        runTest {
            coEvery { finder.next(any(), any(), any()) } returns segment(nextPath, recordStart.plusSeconds(12), 10.0)
            coEvery { sampler.sample(currentPath, any(), any(), any(), any()) } returns List(9) { tileJpeg }
            coEvery { sampler.sample(nextPath, any(), any(), any(), any()) } throws RuntimeException("ffmpeg exited with 1")

            val built = assertNotNull(builder().build(recording(), listOf(frame(4, 10.0))))

            assertTrue(built.storyboard.missingAfter)
            assertEquals(9, built.storyboard.tiles)
            assertEquals(5.28, built.storyboard.durationSeconds, 1e-9)
        }

    /** Запись отдала 10 клеток из 11, следующий сегмент — все 5: в подписях скачок, но отрезок кончается по плану, 5.0..15.0 с. */
    @Test
    fun `a recording that ends early before a full next segment keeps the planned end`() =
        runTest {
            coEvery { finder.next(any(), any(), any()) } returns segment(nextPath, recordStart.plusSeconds(12), 10.0)
            coEvery { sampler.sample(currentPath, any(), any(), any(), any()) } returns List(10) { tileJpeg }

            val built = assertNotNull(builder().build(recording(), listOf(frame(4, 10.0))))

            assertFalse(built.storyboard.missingAfter)
            assertEquals(15, built.storyboard.tiles)
            assertEquals(10.0, built.storyboard.durationSeconds, 1e-9)
        }

    @Test
    fun `too few frames back from ffmpeg give no storyboard`() =
        runTest {
            coEvery { sampler.sample(currentPath, any(), any(), any(), any()) } returns List(3) { tileJpeg }

            assertNull(builder().build(recording(), listOf(frame(2, 6.0))))
        }

    @Test
    fun `too little footage gives no storyboard`() =
        runTest {
            val tight = StoryboardProperties(before = Duration.ZERO, after = Duration.ZERO)

            assertNull(builder(tight).build(recording(), listOf(frame(2, 6.0))))
            coVerify(exactly = 0) { sampler.sample(any(), any(), any(), any(), any()) }
        }

    /** `build` не бросает ничего, кроме отмены: `Error` отсюда погубил бы всё описание, а не одну раскадровку. */
    @Test
    fun `an error rather than an exception gives no storyboard either`() =
        runTest {
            coEvery { probe.probe(currentPath) } throws LinkageError("libfontmanager.so")

            assertNull(builder().build(recording(), listOf(frame(2, 6.0))))
        }

    @Test
    fun `cancellation is not swallowed`() =
        runTest {
            coEvery { probe.probe(currentPath) } throws CancellationException("shutdown")

            assertFailsWith<CancellationException> { builder().build(recording(), listOf(frame(2, 6.0))) }
        }

    /** Держи ожидание семафор — две записи, ждущие следующий сегмент, остановили бы третью. */
    @Test
    fun `waiting for a next segment does not hold a sampling permit`() =
        runTest {
            val gate = CompletableDeferred<Segment?>()
            coEvery { finder.next(any(), any(), any()) } coAnswers { gate.await() }
            val builder = builder()

            val waiting = List(2) { async { builder.build(recording(), listOf(frame(4, 10.0))) } }
            runCurrent()
            val free = builder.build(recording(), listOf(frame(2, 6.0)))

            assertNotNull(free)
            gate.complete(null)
            waiting.awaitAll().forEach { assertNotNull(it) }
        }
}
