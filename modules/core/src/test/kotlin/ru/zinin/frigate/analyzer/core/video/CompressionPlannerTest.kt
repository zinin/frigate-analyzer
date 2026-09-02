package ru.zinin.frigate.analyzer.core.video

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.zinin.frigate.analyzer.core.config.properties.ExportProperties
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompressionPlannerTest {
    private val planner = CompressionPlanner(ExportProperties())

    private fun info(
        width: Int,
        height: Int,
        durationSeconds: Double,
        hasAudio: Boolean = false,
    ) = VideoInfo(
        durationSeconds = durationSeconds,
        width = width,
        height = height,
        fps = 12.5,
        hasAudio = hasAudio,
    )

    @Test
    fun `two minutes of a 4 by 3 5MP camera go to 1080p at the full budget`() {
        val plan = planner.plan(info(2560, 1920, 120.0), TARGET)

        assertEquals(
            CompressionPlan(scaleHeight = 1080, videoMaxrateKbps = 3051, audioBitrateKbps = null, crf = 23, preset = "fast"),
            plan,
        )
    }

    @Test
    fun `two minutes of a 16 by 9 5MP camera go to 1080p`() {
        val plan = planner.plan(info(2880, 1620, 120.0), TARGET)

        assertEquals(1080, plan.scaleHeight)
        assertEquals(3051, plan.videoMaxrateKbps)
    }

    @Test
    fun `five minutes of a 4 by 3 5MP camera go to 720p`() {
        val plan = planner.plan(info(2560, 1920, 300.0), TARGET)

        assertEquals(720, plan.scaleHeight)
        assertEquals(1220, plan.videoMaxrateKbps)
    }

    @Test
    fun `five minutes of a 16 by 9 5MP camera go to 720p`() {
        val plan = planner.plan(info(2880, 1620, 300.0), TARGET)

        assertEquals(720, plan.scaleHeight)
        assertEquals(1220, plan.videoMaxrateKbps)
    }

    @Test
    fun `audio takes 64 kbps out of the video budget and stays in the plan`() {
        val plan = planner.plan(info(2560, 1920, 120.0, hasAudio = true), TARGET)

        assertEquals(2987, plan.videoMaxrateKbps)
        assertEquals(64, plan.audioBitrateKbps)
        assertEquals(1080, plan.scaleHeight)
    }

    @Test
    fun `a 720p source that fits the budget keeps its size without a scale filter`() {
        val plan = planner.plan(info(1280, 720, 120.0), TARGET)

        assertNull(plan.scaleHeight)
    }

    @Test
    fun `a 720p source over a long window falls to the smallest candidate`() {
        val plan = planner.plan(info(1280, 720, 600.0), TARGET)

        assertEquals(540, plan.scaleHeight)
        assertEquals(610, plan.videoMaxrateKbps)
    }

    @Test
    fun `a source below every candidate height is never upscaled`() {
        val plan = planner.plan(info(640, 480, 120.0), TARGET)

        assertNull(plan.scaleHeight)
        assertEquals(3051, plan.videoMaxrateKbps)
    }

    @Test
    fun `scaled width follows the source aspect and is even`() {
        assertEquals(1440, planner.scaledWidth(info(2560, 1920, 1.0), 1080))
        assertEquals(1920, planner.scaledWidth(info(2880, 1620, 1.0), 1080))
        assertEquals(960, planner.scaledWidth(info(2560, 1920, 1.0), 720))
        assertEquals(1280, planner.scaledWidth(info(1366, 768, 1.0), 720))
        assertEquals(500, planner.scaledWidth(info(1001, 1000, 1.0), 500))
    }

    @Test
    fun `plan rejects a non-positive duration`() {
        assertThrows<IllegalArgumentException> { planner.plan(info(2560, 1920, 0.0), TARGET) }
    }

    @Test
    fun `plan rejects a non-positive target`() {
        assertThrows<IllegalArgumentException> { planner.plan(info(2560, 1920, 120.0), 0L) }
    }

    @Test
    fun `plan rejects a non-positive frame size`() {
        assertThrows<IllegalArgumentException> { planner.plan(info(0, 1920, 120.0), TARGET) }
        assertThrows<IllegalArgumentException> { planner.plan(info(2560, 0, 120.0), TARGET) }
    }

    @Test
    fun `plan rejects a non-positive frame rate`() {
        assertThrows<IllegalArgumentException> { planner.plan(info(2560, 1920, 120.0).copy(fps = 0.0), TARGET) }
    }

    @Test
    fun `plan reports VideoTooLargeException when audio alone exhausts the budget`() {
        assertThrows<VideoTooLargeException> { planner.plan(info(2560, 1920, 1_000_000.0, hasAudio = true), TARGET) }
    }

    @Test
    fun `shrink lowers the cap by the overshoot ratio and 10 percent and keeps the height`() {
        val previous = planner.plan(info(2560, 1920, 120.0), TARGET)

        val retry = planner.shrink(previous, info(2560, 1920, 120.0), actualBytes = 52_000_000L, targetBytes = TARGET)

        assertEquals(2491, retry.videoMaxrateKbps)
        assertNull(retry.scaleHeight, "1080p input re-encoded at 1080p needs no scale filter")
        assertEquals(23, retry.crf)
        assertEquals("fast", retry.preset)
    }

    @Test
    fun `shrink steps down to a smaller height when the new cap is too thin`() {
        val previous = planner.plan(info(2560, 1920, 300.0), TARGET)

        val retry = planner.shrink(previous, info(2560, 1920, 300.0), actualBytes = 60_000_000L, targetBytes = TARGET)

        assertEquals(863, retry.videoMaxrateKbps)
        assertEquals(540, retry.scaleHeight)
    }

    @Test
    fun `shrink never goes above the height of the first result`() {
        val previous = CompressionPlan(scaleHeight = 540, videoMaxrateKbps = 1000, audioBitrateKbps = null, crf = 23, preset = "fast")

        val retry = planner.shrink(previous, info(2560, 1920, 120.0), actualBytes = 47_500_000L, targetBytes = TARGET)

        assertNull(retry.scaleHeight, "540p input stays 540p")
        assertEquals(894, retry.videoMaxrateKbps)
    }

    @Test
    fun `shrink keeps the audio bitrate of the first plan`() {
        val previous = planner.plan(info(2560, 1920, 120.0, hasAudio = true), TARGET)

        val retry = planner.shrink(previous, info(2560, 1920, 120.0, hasAudio = true), actualBytes = 52_000_000L, targetBytes = TARGET)

        assertEquals(64, retry.audioBitrateKbps)
    }

    @Test
    fun `shrink rejects a non-positive actual size`() {
        val previous = planner.plan(info(2560, 1920, 120.0), TARGET)

        assertThrows<IllegalArgumentException> { planner.shrink(previous, info(2560, 1920, 120.0), 0L, TARGET) }
    }

    companion object {
        private const val TARGET = 45L * 1024 * 1024
    }
}
