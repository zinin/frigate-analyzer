package ru.zinin.frigate.analyzer.core.helper

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class TempFileHelperTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var helper: TempFileHelper

    private val clock = Clock.fixed(Instant.parse("2026-02-17T10:30:45Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        val properties = ApplicationProperties(
            tempFolder = tempDir,
            ffmpegPath = Path.of("/usr/bin/ffmpeg"),
            connectionTimeout = Duration.ofSeconds(5),
            readTimeout = Duration.ofSeconds(5),
            writeTimeout = Duration.ofSeconds(5),
            responseTimeout = Duration.ofSeconds(5),
        )
        helper = TempFileHelper(properties, clock)
        helper.init()
    }

    @Test
    fun `createTempFile creates empty file with correct prefix`() = runTest {
        val path = helper.createTempFile("test-", ".txt")

        assertTrue(Files.exists(path))
        assertTrue(path.fileName.toString().startsWith("frigate-analyzer-tmp-2026-02-17-10-30-45-test-"))
        assertTrue(path.fileName.toString().endsWith(".txt"))
        assertTrue(path.startsWith(tempDir))
        assertTrue(Files.size(path) == 0L)
    }

    @Test
    fun `createTempFile with byte array writes content`() = runTest {
        val content = "Hello, world!".toByteArray()
        val path = helper.createTempFile("data-", ".bin", content)

        assertTrue(Files.exists(path))
        assertTrue(path.fileName.toString().startsWith("frigate-analyzer-tmp-"))
        assertArrayEquals(content, Files.readAllBytes(path))
    }
}
