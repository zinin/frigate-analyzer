package ru.zinin.frigate.analyzer.core.helper

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoMergeHelperTest {
    @TempDir
    lateinit var tempDir: Path

    private val runner = mockk<FfmpegProcessRunner>()
    private lateinit var helper: VideoMergeHelper

    @BeforeEach
    fun setUp() {
        val properties =
            ApplicationProperties(
                tempFolder = tempDir,
                ffmpegPath = Path.of("/usr/bin/ffmpeg"),
                connectionTimeout = Duration.ofSeconds(5),
                readTimeout = Duration.ofSeconds(5),
                writeTimeout = Duration.ofSeconds(5),
                responseTimeout = Duration.ofSeconds(5),
            )
        val tempFileHelper =
            TempFileHelper(properties, Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC))
        tempFileHelper.init()
        helper = VideoMergeHelper(properties, tempFileHelper, runner)
    }

    private fun sourceFile(name: String): Path = tempDir.resolve(name).also { Files.write(it, byteArrayOf(1, 2, 3)) }

    @Test
    fun `mergeVideos writes an escaped concat list and runs ffmpeg with stream copy`() =
        runTest {
            val first = sourceFile("a.mp4")
            val second = sourceFile("it's.mp4")
            val commands = mutableListOf<List<String>>()
            var concatLines: List<String>? = null
            coEvery { runner.run(capture(commands), Duration.ofSeconds(300)) } coAnswers {
                val command = firstArg<List<String>>()
                concatLines = Files.readAllLines(Path.of(command[command.indexOf("-i") + 1]))
                emptyList()
            }

            val output = helper.mergeVideos(listOf(first, second))

            val command = commands.single()
            val concatPath = Path.of(command[command.indexOf("-i") + 1])
            assertEquals(
                listOf(
                    "/usr/bin/ffmpeg",
                    "-hide_banner",
                    "-nostdin",
                    "-f",
                    "concat",
                    "-safe",
                    "0",
                    "-i",
                    concatPath.toString(),
                    "-c",
                    "copy",
                    "-y",
                    output.toString(),
                ),
                command,
            )
            assertEquals(
                listOf("file '$first'", "file '${tempDir.resolve("it")}'\\''s.mp4'"),
                concatLines,
            )
            assertTrue(output.startsWith(tempDir))
            assertTrue(output.fileName.toString().contains("merged-"))
            assertFalse(Files.exists(concatPath), "concat list must be deleted after the merge")
        }

    @Test
    fun `mergeVideos with a single file copies it without ffmpeg`() =
        runTest {
            val only = sourceFile("only.mp4")

            val output = helper.mergeVideos(listOf(only))

            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(output))
            assertTrue(output.startsWith(tempDir))
            coVerify(exactly = 0) { runner.run(any(), any()) }
        }

    @Test
    fun `mergeVideos deletes the output and the concat list when ffmpeg fails`() =
        runTest {
            val first = sourceFile("a.mp4")
            val second = sourceFile("b.mp4")
            val commands = mutableListOf<List<String>>()
            coEvery { runner.run(capture(commands), any()) } throws RuntimeException("ffmpeg exited with code 1")

            assertThrows<RuntimeException> { helper.mergeVideos(listOf(first, second)) }

            val command = commands.single()
            assertFalse(Files.exists(Path.of(command.last())), "merged output must be deleted")
            assertFalse(Files.exists(Path.of(command[command.indexOf("-i") + 1])), "concat list must be deleted")
        }

    @Test
    fun `mergeVideos rejects an empty list`() =
        runTest {
            assertThrows<IllegalArgumentException> { helper.mergeVideos(emptyList()) }
        }
}
