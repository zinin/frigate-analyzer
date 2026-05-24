package ru.zinin.frigate.analyzer.core.task

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.model.dto.RecordingFileDto
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val ROOT = Path.of("/mnt/data/frigate/recordings")

class WatchRecordsLoopTest {
    private val recordingEntityHelper = mockk<RecordingEntityHelper>()
    private val recordingFileHelper = mockk<RecordingFileHelper>()
    private val properties =
        RecordsWatcherProperties(
            folder = Path.of("/mnt/data/frigate/recordings"),
            watchPeriod = Duration.ofDays(1),
            cleanupInterval = Duration.ofHours(1),
        )
    private val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)

    private val loop =
        WatchRecordsLoop(
            recordsWatcherProperties = properties,
            recordingEntityHelper = recordingEntityHelper,
            recordingFileHelper = recordingFileHelper,
            clock = clock,
        )

    @Test
    fun `runIteration returns zero processed events when poll times out`() =
        runTest {
            val watchService = mockk<WatchService>()
            every { watchService.poll(any<Long>(), any<TimeUnit>()) } returns null
            val dirs = ConcurrentHashMap<Path, WatchKey>()
            val lastCleanup = Instant.now(clock)

            val result = loop.runIteration(watchService, dirs, lastCleanup)

            assertEquals(0, result.eventsProcessed)
            assertEquals(lastCleanup, result.lastCleanupAt)
            assertEquals(0, result.eventFailures)
            verify { watchService.poll(any<Long>(), any<TimeUnit>()) }
        }

    @Test
    fun `runIteration creates recording for new mp4 file`() =
        runTest {
            val watchService = mockk<WatchService>()
            val key = mockk<WatchKey>()
            val event = mockk<WatchEvent<Path>>()

            // Use a real tempdir + real file — Files.* statics are otherwise hard to stub.
            val tmpDir = Files.createTempDirectory("wrl-test")
            try {
                val realDir = tmpDir.resolve("2026-05-23/12/cam1")
                Files.createDirectories(realDir)
                val fileName = Path.of("cam1-2026-05-23-12.14.27.mp4")
                val realFile = realDir.resolve(fileName.toString())
                Files.createFile(realFile)

                every { watchService.poll(any<Long>(), any<TimeUnit>()) } returns key
                every { key.watchable() } returns realDir
                every { key.reset() } returns true
                every { event.kind() } returns StandardWatchEventKinds.ENTRY_CREATE
                every { event.context() } returns fileName
                every { key.pollEvents() } returns listOf(event)

                coEvery { recordingEntityHelper.createRecording(any()) } returns UUID.randomUUID()
                // Use a real RecordingFileDto rather than mockk — DTO is a tiny data class.
                every { recordingFileHelper.parse(any()) } returns
                    RecordingFileDto(
                        basePath = realDir.toAbsolutePath().toString(),
                        camId = "cam1",
                        date = LocalDate.of(2026, 5, 23),
                        time = LocalTime.of(12, 14, 27),
                        timestamp = Instant.parse("2026-05-23T12:14:27Z"),
                    )

                val dirs = ConcurrentHashMap<Path, WatchKey>()
                val result = loop.runIteration(watchService, dirs, Instant.now(clock))

                assertEquals(1, result.eventsProcessed)
                assertEquals(0, result.eventFailures)
                coVerify(exactly = 1) { recordingEntityHelper.createRecording(any()) }
                // file path is not a directory -> registerAllDirs not called
                assertEquals(0, dirs.size)
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `runIteration registers new directory and does not call createRecording`() =
        runTest {
            val tmpDir = Files.createTempDirectory("wrl-test-dir")
            val watchService = FileSystems.getDefault().newWatchService()
            try {
                tmpDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE)
                val loopWithTmpRoot =
                    WatchRecordsLoop(
                        recordsWatcherProperties =
                            RecordsWatcherProperties(
                                folder = tmpDir,
                                watchPeriod = Duration.ofDays(365),
                                cleanupInterval = Duration.ofHours(1),
                            ),
                        recordingEntityHelper = recordingEntityHelper,
                        recordingFileHelper = recordingFileHelper,
                        clock = clock,
                    )
                Files.createDirectory(tmpDir.resolve("2026-05-23"))

                // Retry runIteration up to 5s — OS event delivery is non-deterministic on slow runners
                // (macOS WatchService can lag seconds, CI runners are unpredictable).
                val deadline = System.currentTimeMillis() + 5_000
                var result: IterationResult? = null
                while (System.currentTimeMillis() < deadline) {
                    result = loopWithTmpRoot.runIteration(watchService, ConcurrentHashMap(), Instant.now(clock))
                    if (result.eventsProcessed > 0) break
                }

                coVerify(exactly = 0) { recordingEntityHelper.createRecording(any()) }
                assertEquals(1, result?.eventsProcessed)
                assertEquals(0, result?.eventFailures)
            } finally {
                watchService.close()
                tmpDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `runIteration counts parse failure in eventFailures and continues`() =
        runTest {
            val watchService = mockk<WatchService>()
            val key = mockk<WatchKey>()
            val event = mockk<WatchEvent<Path>>()
            val tmpDir = Files.createTempDirectory("wrl-test-parse")
            try {
                val realDir = tmpDir.resolve("2026-05-23/12/cam1")
                Files.createDirectories(realDir)
                val realFile = realDir.resolve("bogus.mp4")
                Files.createFile(realFile)
                every { watchService.poll(any<Long>(), any<TimeUnit>()) } returns key
                every { key.watchable() } returns realDir
                every { key.reset() } returns true
                every { event.kind() } returns StandardWatchEventKinds.ENTRY_CREATE
                every { event.context() } returns Path.of("bogus.mp4")
                every { key.pollEvents() } returns listOf(event)
                every { recordingFileHelper.parse(any()) } throws IllegalArgumentException("bogus filename")

                val result = loop.runIteration(watchService, ConcurrentHashMap(), Instant.now(clock))

                assertEquals(0, result.eventsProcessed)
                assertEquals(1, result.eventFailures)
                coVerify(exactly = 0) { recordingEntityHelper.createRecording(any()) }
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `runIteration runs cleanup when interval elapsed`() =
        runTest {
            val watchService = mockk<WatchService>()
            every { watchService.poll(any<Long>(), any<TimeUnit>()) } returns null
            val tmpDir = Files.createTempDirectory("wrl-test-cleanup")
            try {
                val oldDirPath = tmpDir.resolve("2025-01-01") // far outside watchPeriod
                Files.createDirectories(oldDirPath)
                val oldKey = mockk<WatchKey>(relaxed = true)
                val dirs = ConcurrentHashMap<Path, WatchKey>().apply { put(oldDirPath, oldKey) }
                val staleLastCleanup = Instant.parse("2026-05-23T10:00:00Z")
                // clock = 2026-05-23T12:00:00Z, cleanupInterval = 1h -> 2h elapsed -> cleanup fires
                val loopWithTmpRoot =
                    WatchRecordsLoop(
                        recordsWatcherProperties =
                            RecordsWatcherProperties(
                                folder = tmpDir,
                                watchPeriod = Duration.ofDays(1),
                                cleanupInterval = Duration.ofHours(1),
                            ),
                        recordingEntityHelper = recordingEntityHelper,
                        recordingFileHelper = recordingFileHelper,
                        clock = clock,
                    )

                val result = loopWithTmpRoot.runIteration(watchService, dirs, staleLastCleanup)

                assertEquals(0, dirs.size, "Old dir should be cleaned up")
                verify { oldKey.cancel() }
                assertNotEquals(staleLastCleanup, result.lastCleanupAt, "lastCleanupAt should advance")
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }

    // --- Pure-function tests migrated from WatchRecordsTaskTest ---

    @Test
    fun `extractDateFromPath returns date for date directory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns date for hour subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns date for camera subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09/cam1")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns null for root recordings directory`() {
        val path = Path.of("/mnt/data/frigate/recordings")
        assertNull(extractDateFromPath(path, ROOT))
    }

    @Test
    fun `isWithinWatchPeriod returns true for today`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns true for yesterday within 1 day period`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-14")
        assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns false for old date`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-01-01")
        assertFalse(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns true for root directory without date`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings")
        assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns true for exact cutoff date`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-14")
        assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns false for one day before cutoff`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-13")
        assertFalse(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), clock))
    }

    @Test
    fun `extractDateFromPath returns null for invalid date like 2026-02-30`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-30")
        assertNull(extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath ignores date-like segments in root path`() {
        val rootWithDate = Path.of("/data/2024-01-15/frigate/recordings")
        val path = Path.of("/data/2024-01-15/frigate/recordings/2026-02-15/09/cam1")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, rootWithDate))
    }
}
