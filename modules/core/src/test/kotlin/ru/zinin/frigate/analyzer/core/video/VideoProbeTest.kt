package ru.zinin.frigate.analyzer.core.video

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoProbeTest {
    private val runner = mockk<FfmpegProcessRunner>()
    private val properties =
        ApplicationProperties(
            tempFolder = Path.of("/tmp/frigate-analyzer-test"),
            ffmpegPath = Path.of("/usr/bin/ffmpeg"),
            ffprobePath = Path.of("/opt/tools/ffprobe"),
            connectionTimeout = Duration.ofSeconds(5),
            readTimeout = Duration.ofSeconds(5),
            writeTimeout = Duration.ofSeconds(5),
            responseTimeout = Duration.ofSeconds(5),
        )
    private val probe = VideoProbe(properties, runner, JsonMapper.builder().build())
    private val path: Path = Path.of("/data/merged.mp4")

    private val videoAndAudio =
        """
        {
          "streams": [
            {"codec_type": "video", "width": 2560, "height": 1920, "r_frame_rate": "25/2", "avg_frame_rate": "25/2"},
            {"codec_type": "audio", "r_frame_rate": "0/0", "avg_frame_rate": "0/0"}
          ],
          "format": {"duration": "120.064000"}
        }
        """.trimIndent()

    @Test
    fun `probe runs ffprobe with json output and parses the result`() =
        runTest {
            val commands = mutableListOf<List<String>>()
            coEvery { runner.run(capture(commands), Duration.ofSeconds(30)) } returns videoAndAudio.lines()

            val info = probe.probe(path)

            assertEquals(
                VideoInfo(durationSeconds = 120.064, width = 2560, height = 1920, fps = 12.5, hasAudio = true),
                info,
            )
            assertEquals(
                listOf(
                    "/opt/tools/ffprobe",
                    "-v",
                    "error",
                    "-show_entries",
                    "stream=codec_type,width,height,avg_frame_rate,r_frame_rate:format=duration",
                    "-of",
                    "json",
                    "/data/merged.mp4",
                ),
                commands.single(),
            )
            coVerify(exactly = 1) { runner.run(any(), any()) }
        }

    @Test
    fun `parse reports no audio when there is no audio stream`() {
        val json =
            """
            {"streams": [{"codec_type": "video", "width": 1280, "height": 720, "avg_frame_rate": "25/1"}],
             "format": {"duration": "10.5"}}
            """.trimIndent()

        val info = probe.parse(json, path)

        assertFalse(info.hasAudio)
        assertEquals(25.0, info.fps)
        assertEquals(10.5, info.durationSeconds)
    }

    @Test
    fun `parse falls back to r_frame_rate when avg_frame_rate is unusable`() {
        val json =
            """
            {"streams": [{"codec_type": "video", "width": 1280, "height": 720, "avg_frame_rate": "0/0", "r_frame_rate": "25/2"}],
             "format": {"duration": "10"}}
            """.trimIndent()

        assertEquals(12.5, probe.parse(json, path).fps)
    }

    @Test
    fun `parse assumes 25 fps when both frame rates are unusable`() {
        val json =
            """
            {"streams": [{"codec_type": "video", "width": 1280, "height": 720, "avg_frame_rate": "0/0", "r_frame_rate": "garbage"}],
             "format": {"duration": "10"}}
            """.trimIndent()

        assertEquals(25.0, probe.parse(json, path).fps)
    }

    @Test
    fun `parse rejects output without a video stream with a plain RuntimeException`() {
        val json = """{"streams": [{"codec_type": "audio"}], "format": {"duration": "10"}}"""

        val exception = assertThrows<RuntimeException> { probe.parse(json, path) }

        assertTrue(exception.message!!.contains("no video stream"), exception.message)
        assertEquals(RuntimeException::class, exception::class, "must not be IllegalStateException")
    }

    @Test
    fun `parse rejects a zero frame size with a plain RuntimeException`() {
        val json =
            """
            {"streams": [{"codec_type": "video", "width": 0, "height": 1080, "avg_frame_rate": "25/1"}],
             "format": {"duration": "10"}}
            """.trimIndent()

        val exception = assertThrows<RuntimeException> { probe.parse(json, path) }

        assertTrue(exception.message!!.contains("width"), exception.message)
        assertEquals(RuntimeException::class, exception::class, "must not be IllegalStateException")
    }

    @Test
    fun `parse rejects output without duration`() {
        val json = """{"streams": [{"codec_type": "video", "width": 1280, "height": 720}], "format": {}}"""

        val exception = assertThrows<RuntimeException> { probe.parse(json, path) }

        assertTrue(exception.message!!.contains("duration"), exception.message)
    }

    @Test
    fun `parse rejects unreadable json`() {
        val exception = assertThrows<RuntimeException> { probe.parse("not json at all", path) }

        assertTrue(exception.message!!.contains("unreadable JSON"), exception.message)
    }

    @Test
    fun `parseFrameRate handles rationals, decimals and garbage`() {
        assertEquals(12.5, probe.parseFrameRate("25/2"))
        assertEquals(12.5, probe.parseFrameRate("12.5"))
        assertEquals(30.0, probe.parseFrameRate("30/1"))
        assertNull(probe.parseFrameRate("0/0"))
        assertNull(probe.parseFrameRate("30/0"))
        assertNull(probe.parseFrameRate("garbage"))
        assertNull(probe.parseFrameRate(""))
        assertNull(probe.parseFrameRate(null))
    }
}
