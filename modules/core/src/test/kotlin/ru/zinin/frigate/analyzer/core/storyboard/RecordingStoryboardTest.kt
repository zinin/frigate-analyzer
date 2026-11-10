package ru.zinin.frigate.analyzer.core.storyboard

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.response.BBox
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.Detection
import ru.zinin.frigate.analyzer.model.response.ImageSize
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingStoryboardTest {
    private val builder = mockk<StoryboardBuilder>()
    private val recording =
        RecordingDto(
            id = UUID.randomUUID(),
            creationTimestamp = Instant.parse("2026-09-23T10:00:12Z"),
            filePath = "/rec/cam1/00.00.mp4",
            fileCreationTimestamp = Instant.parse("2026-09-23T10:00:12Z"),
            camId = "cam1",
            recordDate = LocalDate.of(2026, 9, 23),
            recordTime = LocalTime.of(10, 0),
            recordTimestamp = Instant.parse("2026-09-23T10:00:00Z"),
            startProcessingTimestamp = null,
            processTimestamp = null,
            processAttempts = 0,
            detectionsCount = 1,
            analyzeTime = 0,
            analyzedFramesCount = 5,
            errorMessage = null,
        )
    private val built =
        BuiltStoryboard(
            DescriptionRequest.Storyboard(
                image = byteArrayOf(1, 2, 3),
                tiles = 16,
                stepSeconds = 0.66,
                durationSeconds = 10.0,
                detections = listOf(DescriptionRequest.DetectionMark("car", 0.9, 5.0)),
                missingBefore = false,
                missingAfter = false,
            ),
            zeroSeconds = 1.0,
        )

    private fun detection(
        className: String,
        confidence: Double,
    ) = Detection(2, className, confidence, BBox(0.0, 0.0, 1.0, 1.0))

    private fun frame(
        index: Int,
        offset: Double?,
        jpeg: ByteArray = byteArrayOf(1, 2, 3),
        detections: List<Detection> = listOf(detection("car", 0.9)),
    ) = FrameData(
        recordId = recording.id,
        frameIndex = index,
        frameBytes = jpeg,
        detectResponse = DetectResponse(detections, 0, ImageSize(2880, 1620), "m"),
        offsetSeconds = offset,
    )

    private fun storyboard(frames: List<FrameData> = listOf(frame(2, 6.0))) = RecordingStoryboard(recording, frames, builder)

    /** Объект создаётся на каждое событие, а сетка нужна не каждому: до первого обращения видео не читается. */
    @Test
    fun `construction does not build`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } returns built

            val storyboard = storyboard()
            runCurrent()

            coVerify(exactly = 0) { builder.build(any(), any(), any()) }
            storyboard.get()
            coVerify(exactly = 1) { builder.build(any(), any(), any()) }
        }

    /** Judge и описание просят сетку одновременно: собирает первый, второй ждёт его под mutex и получает тот же объект. */
    @Test
    fun `concurrent consumers share one result`() =
        runTest {
            val gate = CompletableDeferred<BuiltStoryboard?>()
            coEvery { builder.build(any(), any(), any()) } coAnswers { gate.await() }
            val storyboard = storyboard()

            val first = async { storyboard.get() }
            val second = async { storyboard.get() }
            runCurrent()
            gate.complete(built)
            val firstResult = first.await()
            val secondResult = second.await()

            assertSame(firstResult, secondResult)
            assertEquals(StoryboardPreparation.Status.AVAILABLE, firstResult.status)
            assertSame(built, firstResult.built)
            coVerify(exactly = 1) { builder.build(any(), any(), any()) }
        }

    /** «Сетки нет» — тоже результат: описание после judge не повторяет сборку, которая уже отказала. */
    @Test
    fun `unavailable result is memoized`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } returns null
            val storyboard = storyboard()

            val first = storyboard.get(Duration.ofSeconds(10), waitForNextSegment = false)
            val second = storyboard.get()

            assertEquals(StoryboardPreparation.Status.UNAVAILABLE, first.status)
            assertNull(first.built)
            assertSame(first, second)
            coVerify(exactly = 1) { builder.build(any(), any(), any()) }
        }

    /**
     * Собственный бюджет истёк: сборка отменена, исход запомнен, и второй потребитель, уже без бюджета, новую
     * сборку не ждёт — виртуальное время стоит на 10 с, а не на 21.
     */
    @Test
    fun `own timeout is memoized`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } coAnswers {
                delay(11_000)
                built
            }
            val storyboard = storyboard()

            val first = storyboard.get(Duration.ofSeconds(10))
            val second = storyboard.get()

            assertEquals(StoryboardPreparation.Status.TIMEOUT, first.status)
            assertNull(first.built)
            assertSame(first, second)
            assertEquals(10_000L, currentTime)
            coVerify(exactly = 1) { builder.build(any(), any(), any()) }
        }

    /**
     * Timeout родителя — не бюджет раскадровки: из get выходит отмена, а не TIMEOUT, исход не запоминается, и
     * следующий живой потребитель собирает сетку заново.
     */
    @Test
    fun `parent timeout is not own timeout`() =
        runTest {
            var calls = 0
            coEvery { builder.build(any(), any(), any()) } coAnswers {
                if (++calls == 1) delay(5_000)
                built
            }
            val storyboard = storyboard()

            var returned: StoryboardPreparation? = null
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1.seconds) { returned = storyboard.get(Duration.ofSeconds(10)) }
            }
            assertNull(returned, "the parent's timeout must leave get as a cancellation, not as a result")
            val retried = storyboard.get(Duration.ofSeconds(10))

            assertEquals(StoryboardPreparation.Status.AVAILABLE, retried.status)
            assertSame(built, retried.built)
            coVerify(exactly = 2) { builder.build(any(), any(), any()) }
        }

    /** Отменили второго, ждущего mutex: он уходит сразу, первый достраивает сетку, третий получает его результат. */
    @Test
    fun `cancelled mutex waiter does not invalidate owner`() =
        runTest {
            val gate = CompletableDeferred<BuiltStoryboard?>()
            coEvery { builder.build(any(), any(), any()) } coAnswers { gate.await() }
            val storyboard = storyboard()

            val owner = async { storyboard.get() }
            runCurrent()
            val waiter = async { storyboard.get() }
            runCurrent()
            waiter.cancel()
            runCurrent()
            assertTrue(waiter.isCancelled && waiter.isCompleted, "the waiter must leave without waiting for the build")
            gate.complete(built)
            val first = owner.await()
            val third = storyboard.get()

            assertEquals(StoryboardPreparation.Status.AVAILABLE, first.status)
            assertSame(first, third)
            coVerify(exactly = 1) { builder.build(any(), any(), any()) }
        }

    /** Отменённая сборка — не результат: её не запоминают, и следующий потребитель собирает сетку заново. */
    @Test
    fun `cancelled builder can be retried`() =
        runTest {
            var calls = 0
            coEvery { builder.build(any(), any(), any()) } coAnswers {
                if (++calls == 1) awaitCancellation()
                built
            }
            val storyboard = storyboard()

            val first = async { storyboard.get() }
            runCurrent()
            first.cancel()
            runCurrent()
            val second = storyboard.get()

            assertTrue(first.isCancelled)
            assertEquals(StoryboardPreparation.Status.AVAILABLE, second.status)
            assertSame(built, second.built)
            coVerify(exactly = 2) { builder.build(any(), any(), any()) }
        }

    /**
     * Builder получает свой список: только кадры с детекциями, без JPEG, с их временем и детекциями. Список берётся
     * при создании — вызывающий вправе сразу отпустить свой, — а исходные кадры остаются нетронутыми.
     */
    @Test
    fun `builder receives only detection metadata`() =
        runTest {
            val car = frame(0, 1.0, jpeg = byteArrayOf(1, 2, 3))
            val empty = frame(1, 2.0, jpeg = byteArrayOf(4, 5, 6), detections = emptyList())
            val person = frame(2, null, jpeg = byteArrayOf(7, 8, 9), detections = listOf(detection("person", 0.7)))
            val input = mutableListOf(car, empty, person)
            val captured = slot<List<FrameData>>()
            coEvery { builder.build(any(), capture(captured), any()) } returns built

            val storyboard = storyboard(input)
            input.clear()
            storyboard.get()

            val frames = captured.captured
            assertEquals(listOf(0, 2), frames.map { it.frameIndex })
            assertTrue(frames.all { it.frameBytes.isEmpty() })
            assertEquals(listOf(1.0, null), frames.map { it.offsetSeconds })
            assertEquals(listOf(car.detectResponse, person.detectResponse), frames.map { it.detectResponse })
            assertContentEquals(byteArrayOf(1, 2, 3), car.frameBytes)
            assertContentEquals(byteArrayOf(4, 5, 6), empty.frameBytes)
            assertContentEquals(byteArrayOf(7, 8, 9), person.frameBytes)
        }

    /** По переданным часам сборка шла 1.5 с: ни виртуальное время теста, ни часы сервера столько не дали бы. */
    @Test
    fun `elapsed uses injected monotonic source`() =
        runTest {
            val time = TestTimeSource()
            coEvery { builder.build(any(), any(), any()) } coAnswers {
                time += 1500.milliseconds
                built
            }

            val prepared = RecordingStoryboard(recording, listOf(frame(2, 6.0)), builder, time).get()

            assertEquals(Duration.ofMillis(1500), prepared.elapsed)
        }

    /**
     * Ждать ли следующий сегмент, решает первый потребитель: judge не ждёт, описание ждёт. Второй получает готовый
     * результат, какой бы флаг ни передал.
     */
    @Test
    fun `wait flag reaches builder`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } returns built
            val judgedFirst = storyboard()
            val describedFirst = storyboard()

            val judged = judgedFirst.get(Duration.ofSeconds(10), waitForNextSegment = false)
            val described = describedFirst.get(waitForNextSegment = true)

            assertSame(judged, judgedFirst.get(waitForNextSegment = true))
            assertSame(described, describedFirst.get(Duration.ofSeconds(10), waitForNextSegment = false))
            coVerify(exactly = 1) { builder.build(any(), any(), false) }
            coVerify(exactly = 1) { builder.build(any(), any(), true) }
        }
}
