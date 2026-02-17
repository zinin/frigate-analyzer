package ru.zinin.frigate.analyzer.core.helper

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.nio.file.Files
import java.io.ByteArrayOutputStream
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

    @Test
    fun `createTempFile with flow writes chunked content`() = runTest {
        val chunk1 = "Hello, ".toByteArray()
        val chunk2 = "world!".toByteArray()
        val content = flowOf(chunk1, chunk2)

        val path = helper.createTempFile("flow-", ".bin", content)

        assertTrue(Files.exists(path))
        assertArrayEquals("Hello, world!".toByteArray(), Files.readAllBytes(path))
    }

    @Test
    fun `readFile returns file content in chunks`() = runTest {
        val content = ByteArray(100_000) { (it % 256).toByte() }
        val path = helper.createTempFile("read-", ".bin", content)

        val result = ByteArrayOutputStream()
        helper.readFile(path, bufferSize = 1024).collect { chunk ->
            result.write(chunk)
        }

        assertArrayEquals(content, result.toByteArray())
    }

    @Test
    fun `readFile returns empty flow for empty file`() = runTest {
        val path = helper.createTempFile("empty-", ".bin")

        val chunks = mutableListOf<ByteArray>()
        helper.readFile(path).collect { chunks.add(it) }

        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `deleteIfExists deletes file and returns true`() = runTest {
        val path = helper.createTempFile("del-", ".tmp")
        assertTrue(Files.exists(path))

        val result = helper.deleteIfExists(path)

        assertTrue(result)
        assertTrue(Files.notExists(path))
    }

    @Test
    fun `deleteIfExists returns false for non-existent file`() = runTest {
        val path = tempDir.resolve("non-existent.tmp")

        val result = helper.deleteIfExists(path)

        assertFalse(result)
    }

    @Test
    fun `deleteFiles deletes multiple files and returns count`() = runTest {
        val path1 = helper.createTempFile("multi1-", ".tmp")
        val path2 = helper.createTempFile("multi2-", ".tmp")
        val nonExistent = tempDir.resolve("gone.tmp")

        val count = helper.deleteFiles(listOf(path1, path2, nonExistent))

        assertEquals(2, count)
        assertTrue(Files.notExists(path1))
        assertTrue(Files.notExists(path2))
    }
}
