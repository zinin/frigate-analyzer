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
import kotlin.math.roundToInt
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

    /**
     * 6 с по [TIMED_FPS] кадров/с; в кадре N белая полоса от левого края шириной (N + 1) × 10 px —
     * номер кадра читается и после JPEG.
     */
    private suspend fun makeTimedVideo(): Path {
        val timed = tempDir.resolve("timed.mp4")
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
                "color=c=black:size=640x360:rate=$TIMED_FPS:duration=6,format=yuv420p," +
                    "geq=lum='if(lt(X,10*(N+1)),235,16)':cb=128:cr=128",
                "-c:v",
                "mpeg4",
                "-q:v",
                "2",
                timed.toString(),
            ),
            Duration.ofMinutes(1),
        )
        return timed
    }

    /** Время кадра, который показывает клетка: ширина полосы — светлые пиксели средней строки. */
    private fun shownSeconds(jpeg: ByteArray): Double {
        val image = ImageIO.read(ByteArrayInputStream(jpeg))
        val row = image.height / 2
        val bar = (0 until image.width).count { x -> (image.getRGB(x, row) shr 8 and 0xFF) > 128 }
        return ((bar / 10.0).roundToInt() - 1) / TIMED_FPS.toDouble()
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
    fun `a moment just before the end of the file still gets its frame`() =
        runTest(timeout = 2.minutes) {
            makeVideo()

            // Последний момент 9.9 с — за 0.1 с до конца файла, как последняя клетка у планировщика.
            val frames = sampler.sample(video, firstSeconds = 0.0, stepSeconds = 0.66, count = 16, tileWidth = 320)

            assertEquals(16, frames.size)
        }

    @Test
    fun `each tile shows the last frame at or before its moment`() =
        runTest(timeout = 2.minutes) {
            val timed = makeTimedVideo()
            // Переход между кадрами: без start_time=0 фильтр сдвинул бы все клетки на шаг.
            val moments = List(6) { 1.05 + it * 0.66 }

            val shown = sampler.sample(timed, moments.first(), 0.66, moments.size, tileWidth = 640).map(::shownSeconds)

            assertEquals(moments.size, shown.size)
            moments.zip(shown).forEachIndexed { index, (moment, seconds) ->
                // Кадры раньше точки перехода ffmpeg отбрасывает, поэтому первой клетке достаётся первый кадр после неё.
                val allowed = if (index == 0) moment..(moment + FRAME_SECONDS) else (moment - FRAME_SECONDS)..moment
                assertTrue(seconds in allowed, "tile $index at $moment s shows $seconds s; moments $moments, shown $shown")
            }
        }

    @Test
    fun `a missing file fails`() =
        runTest(timeout = 2.minutes) {
            assertFailsWith<RuntimeException> {
                sampler.sample(tempDir.resolve("missing.mp4"), 0.0, 1.0, 3, 320)
            }
            assertTrue(tempDir.resolve("tmp").listDirectoryEntries("storyboard-*").isEmpty())
        }

    companion object {
        private const val TIMED_FPS = 10
        private const val FRAME_SECONDS = 1.0 / TIMED_FPS
    }
}
