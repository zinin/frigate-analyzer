package ru.zinin.frigate.analyzer.core.video

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.config.properties.ExportProperties
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.helper.VideoMergeHelper
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Runs the real ffmpeg/ffprobe binaries. Skipped (reported as such, not as passed) when they are
 * not found at /usr/bin, or at `FFMPEG_PATH` / `FFPROBE_PATH` when those are set; CI installs
 * them with apt before the build.
 */
class FfmpegCompressionIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val ffmpeg: Path = Path.of(System.getenv("FFMPEG_PATH") ?: "/usr/bin/ffmpeg")
    private val ffprobe: Path = Path.of(System.getenv("FFPROBE_PATH") ?: "/usr/bin/ffprobe")

    @BeforeEach
    fun requireTools() {
        assumeTrue(Files.isExecutable(ffmpeg), "ffmpeg not found at $ffmpeg, skipping")
        assumeTrue(Files.isExecutable(ffprobe), "ffprobe not found at $ffprobe, skipping")
    }

    @Test
    fun `fitter probes, plans and re-encodes a real video under the limit`() =
        runTest(timeout = 5.minutes) {
            val properties =
                ApplicationProperties(
                    tempFolder = tempDir,
                    ffmpegPath = ffmpeg,
                    ffprobePath = ffprobe,
                    connectionTimeout = Duration.ofSeconds(5),
                    readTimeout = Duration.ofSeconds(5),
                    writeTimeout = Duration.ofSeconds(5),
                    responseTimeout = Duration.ofSeconds(5),
                )
            val tempFileHelper = TempFileHelper(properties, Clock.systemUTC())
            tempFileHelper.init()
            val runner = FfmpegProcessRunner()
            val probe = VideoProbe(properties, runner, JsonMapper.builder().build())
            val planner = CompressionPlanner(ExportProperties())
            val mergeHelper = VideoMergeHelper(properties, tempFileHelper, runner)
            val limits = FitLimits(thresholdBytes = 1L * 1024 * 1024, maxBytes = 1_250_000L)
            val fitter = TelegramVideoFitter(probe, planner, mergeHelper, tempFileHelper, limits)

            // 20 s of synthetic 720p video with a sine tone, near-lossless so that it is well above
            // the 1 MiB threshold.
            val source = tempFileHelper.createTempFile("source-", ".mp4")
            runner.run(
                listOf(
                    ffmpeg.toString(),
                    "-hide_banner",
                    "-nostdin",
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=size=1280x720:rate=12.5",
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=440:sample_rate=48000",
                    "-t",
                    "20",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "ultrafast",
                    "-crf",
                    "5",
                    "-pix_fmt",
                    "yuv420p",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "128k",
                    "-shortest",
                    "-y",
                    source.toString(),
                ),
                Duration.ofMinutes(2),
            )
            val sourceSize = Files.size(source)
            assertTrue(sourceSize > limits.thresholdBytes, "synthetic source must exceed the threshold, got $sourceSize bytes")

            val info = probe.probe(source)
            assertEquals(1280, info.width)
            assertEquals(720, info.height)
            assertEquals(12.5, info.fps, 0.01)
            assertEquals(20.0, info.durationSeconds, 0.5)
            assertTrue(info.hasAudio)

            // 1 MiB over 20 s leaves ~342 kbps for video: too thin for 720p and 540p at 0.1 bpp,
            // so the planner falls to the smallest candidate.
            val plan = planner.plan(info, limits.thresholdBytes)
            assertEquals(540, plan.scaleHeight)
            assertEquals(64, plan.audioBitrateKbps)

            var compressStarted = false
            val result = fitter.fit(source) { compressStarted = true }

            assertTrue(compressStarted)
            val resultSize = Files.size(result)
            assertTrue(resultSize <= limits.maxBytes, "result is $resultSize bytes, limit is ${limits.maxBytes}")
            assertFalse(Files.exists(source), "the fitter deletes its input after success")

            val resultInfo = probe.probe(result)
            assertEquals(540, resultInfo.height)
            assertEquals(960, resultInfo.width)
            assertTrue(resultInfo.hasAudio)
            assertEquals(20.0, resultInfo.durationSeconds, 0.5)
        }
}
