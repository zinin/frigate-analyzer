package ru.zinin.frigate.analyzer.core.storyboard

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunner
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Настоящий ffmpeg на синтетическом видео. Пропускается (как skipped, не как passed), если ffmpeg
 * нет в `/usr/bin` или в `FFMPEG_PATH`; CI ставит его через apt.
 */
class StoryboardFrameSamplerIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val ffmpeg: Path = Path.of(System.getenv("FFMPEG_PATH") ?: "/usr/bin/ffmpeg")
    private val runner = FfmpegProcessRunner()
    private lateinit var sampler: StoryboardFrameSampler
    private lateinit var video: Path

    @BeforeEach
    fun setUp() {
        assumeTrue(Files.isExecutable(ffmpeg), "ffmpeg not found at $ffmpeg, skipping")
        val properties =
            ApplicationProperties(
                tempFolder = tempDir.resolve("tmp"),
                ffmpegPath = ffmpeg,
                connectionTimeout = Duration.ofSeconds(5),
                readTimeout = Duration.ofSeconds(5),
                writeTimeout = Duration.ofSeconds(5),
                responseTimeout = Duration.ofSeconds(5),
            )
        val tempFileHelper = TempFileHelper(properties, Clock.systemUTC()).also { it.init() }
        sampler = StoryboardFrameSampler(properties, runner, tempFileHelper)
        video = tempDir.resolve("segment.mp4")
    }

    private suspend fun makeVideo() {
        runner.run(
            listOf(
                ffmpeg.toString(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=10:size=640x360:rate=20",
                "-c:v",
                "mpeg4",
                "-q:v",
                "5",
                video.toString(),
            ),
            Duration.ofMinutes(1),
        )
    }

    @Test
    fun `samples the requested frames at the tile width`() =
        runTest(timeout = 2.minutes) {
            makeVideo()

            val frames = sampler.sample(video, firstSeconds = 1.0, stepSeconds = 1.0, count = 5, tileWidth = 320)

            assertEquals(5, frames.size)
            frames.forEach { bytes ->
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                assertEquals(320, image.width)
                assertEquals(180, image.height)
            }
            assertFalse(frames.first().contentEquals(frames.last()), "testsrc changes over time; the frames must differ")
            assertTrue(tempDir.resolve("tmp").listDirectoryEntries("storyboard-*").isEmpty(), "sampled files are deleted")
        }

    @Test
    fun `a file that ends early gives fewer frames`() =
        runTest(timeout = 2.minutes) {
            makeVideo()

            val frames = sampler.sample(video, firstSeconds = 8.0, stepSeconds = 1.0, count = 5, tileWidth = 320)

            assertTrue(frames.size in 1..3, "expected the frames at 8 and 9 s (maybe one more), got ${frames.size}")
        }

    @Test
    fun `a missing file fails`() =
        runTest(timeout = 2.minutes) {
            assertFailsWith<RuntimeException> {
                sampler.sample(tempDir.resolve("missing.mp4"), 0.0, 1.0, 3, 320)
            }
            assertTrue(tempDir.resolve("tmp").listDirectoryEntries("storyboard-*").isEmpty())
        }
}
