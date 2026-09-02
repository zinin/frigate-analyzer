package ru.zinin.frigate.analyzer.core.video

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.helper.VideoMergeHelper
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramVideoFitterTest {
    @TempDir
    lateinit var tempDir: Path

    private val probe = mockk<VideoProbe>()
    private val planner = mockk<CompressionPlanner>()
    private val mergeHelper = mockk<VideoMergeHelper>()
    private val tempFileHelper = mockk<TempFileHelper>()
    private val limits = FitLimits(thresholdBytes = 1000, maxBytes = 1200)
    private val fitter = TelegramVideoFitter(probe, planner, mergeHelper, tempFileHelper, limits)

    private val info = VideoInfo(durationSeconds = 120.0, width = 2560, height = 1920, fps = 12.5, hasAudio = false)
    private val plan = CompressionPlan(scaleHeight = 1080, videoMaxrateKbps = 3051, audioBitrateKbps = null, crf = 23, preset = "fast")
    private val retryPlan = CompressionPlan(scaleHeight = null, videoMaxrateKbps = 2491, audioBitrateKbps = null, crf = 23, preset = "fast")

    @BeforeEach
    fun setUp() {
        coEvery { tempFileHelper.deleteIfExists(any()) } returns true
    }

    private fun file(
        name: String,
        size: Long,
    ): Path =
        tempDir.resolve(name).also { path ->
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(size) }
        }

    @Test
    fun `returns the input untouched when it is within the threshold`() =
        runTest {
            val input = file("small.mp4", 1000)
            var started = false

            val result = fitter.fit(input) { started = true }

            assertEquals(input, result)
            assertFalse(started, "callback must not fire without compression")
            coVerify(exactly = 0) { probe.probe(any()) }
            coVerify(exactly = 0) { mergeHelper.compressVideo(any(), any<CompressionPlan>()) }
            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(any()) }
        }

    @Test
    fun `compresses once and deletes the input when the first attempt fits`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1100)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first
            var starts = 0

            val result = fitter.fit(input) { starts++ }

            assertEquals(first, result)
            assertEquals(1, starts)
            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(input) }
            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(first) }
            verify(exactly = 0) { planner.shrink(any(), any(), any(), any()) }
        }

    @Test
    fun `accepts a first result between the threshold and the limit`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1200)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first

            assertEquals(first, fitter.fit(input))
            verify(exactly = 0) { planner.shrink(any(), any(), any(), any()) }
        }

    @Test
    fun `retries from the first result when it overshoots and deletes the first result`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1300)
            val second = file("second.mp4", 1150)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            every { planner.shrink(plan, info, 1300L, 1000L) } returns retryPlan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first
            coEvery { mergeHelper.compressVideo(first, retryPlan) } returns second
            var starts = 0

            val result = fitter.fit(input) { starts++ }

            assertEquals(second, result)
            assertEquals(1, starts, "callback fires once, not per attempt")
            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(first) }
            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(input) }
            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(second) }
        }

    @Test
    fun `throws VideoTooLargeException and deletes both results when the retry still overshoots`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1300)
            val second = file("second.mp4", 1250)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            every { planner.shrink(plan, info, 1300L, 1000L) } returns retryPlan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first
            coEvery { mergeHelper.compressVideo(first, retryPlan) } returns second

            val exception = assertThrows<VideoTooLargeException> { fitter.fit(input) }

            assertTrue(exception.message!!.contains("two compression attempts"), exception.message)
            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(first) }
            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(second) }
            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(input) }
        }

    @Test
    fun `leaves the input to the caller when the first encode fails`() =
        runTest {
            val input = file("big.mp4", 5000)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            coEvery { mergeHelper.compressVideo(input, plan) } throws RuntimeException("ffmpeg exited with code 1")

            assertThrows<RuntimeException> { fitter.fit(input) }

            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(any()) }
        }

    @Test
    fun `deletes the first result when the retry is cancelled`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1300)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            every { planner.shrink(plan, info, 1300L, 1000L) } returns retryPlan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first
            coEvery { mergeHelper.compressVideo(first, retryPlan) } throws CancellationException("export cancelled")

            assertThrows<CancellationException> { fitter.fit(input) }

            coVerify(exactly = 1) { tempFileHelper.deleteIfExists(first) }
            coVerify(exactly = 0) { tempFileHelper.deleteIfExists(input) }
        }

    @Test
    fun `finishes the success cleanup even when cancellation arrives in the middle of it`() =
        runTest {
            val input = file("big.mp4", 5000)
            val first = file("first.mp4", 1300)
            val second = file("second.mp4", 1150)
            coEvery { probe.probe(input) } returns info
            every { planner.plan(info, 1000L) } returns plan
            every { planner.shrink(plan, info, 1300L, 1000L) } returns retryPlan
            coEvery { mergeHelper.compressVideo(input, plan) } returns first
            coEvery { mergeHelper.compressVideo(first, retryPlan) } returns second
            val deleted = mutableListOf<Path>()
            var export: Job? = null
            coEvery { tempFileHelper.deleteIfExists(any()) } coAnswers {
                val path = firstArg<Path>()
                if (path == first) export?.cancel()
                // deleteIfExists suspends through withContext: in a cancelled coroutine it throws
                // unless the cleanup runs under NonCancellable.
                currentCoroutineContext().ensureActive()
                deleted.add(path)
                true
            }

            val job = launch { fitter.fit(input) }
            export = job
            job.join()

            assertEquals(listOf(first, input), deleted)
        }

    @Test
    fun `TELEGRAM limits are the 45 MiB threshold and the decimal 50 MB maximum`() {
        assertEquals(47_185_920L, FitLimits.TELEGRAM.thresholdBytes)
        assertEquals(50_000_000L, FitLimits.TELEGRAM.maxBytes)
    }

    @Test
    fun `FitLimits rejects a threshold above the maximum`() {
        assertThrows<IllegalArgumentException> { FitLimits(thresholdBytes = 2000, maxBytes = 1000) }
    }
}
