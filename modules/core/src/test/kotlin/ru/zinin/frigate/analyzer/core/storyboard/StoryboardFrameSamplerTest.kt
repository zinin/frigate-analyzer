package ru.zinin.frigate.analyzer.core.storyboard

import io.mockk.mockk
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryboardFrameSamplerTest {
    private val properties =
        ApplicationProperties(
            tempFolder = Path.of("/tmp/fa"),
            ffmpegPath = Path.of("/usr/bin/ffmpeg"),
            connectionTimeout = Duration.ofSeconds(5),
            readTimeout = Duration.ofSeconds(5),
            writeTimeout = Duration.ofSeconds(5),
            responseTimeout = Duration.ofSeconds(5),
        )
    private val sampler = StoryboardFrameSampler(properties, mockk(), mockk())

    @Test
    fun `seeks to the first moment and samples at the storyboard step`() {
        val command =
            sampler.command(
                Path.of("/rec/cam1/10.00.mp4"),
                firstSeconds = 3.25,
                stepSeconds = 0.66,
                count = 7,
                tileWidth = 640,
                output = Path.of("/tmp/fa/storyboard-x-%03d.jpg"),
            )

        assertEquals("/usr/bin/ffmpeg", command.first())
        assertEquals("/tmp/fa/storyboard-x-%03d.jpg", command.last())
        assertEquals("3.250", command[command.indexOf("-ss") + 1])
        assertTrue(command.indexOf("-ss") < command.indexOf("-i"), "-ss must be an input option: an accurate and fast seek")
        assertEquals("/rec/cam1/10.00.mp4", command[command.indexOf("-i") + 1])
        assertEquals("fps=1.515152:start_time=0:round=up,scale=640:-2", command[command.indexOf("-vf") + 1])
        assertEquals("7", command[command.indexOf("-frames:v") + 1])
    }
}
