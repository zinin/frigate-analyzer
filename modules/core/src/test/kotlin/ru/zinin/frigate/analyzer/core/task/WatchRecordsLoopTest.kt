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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.model.dto.RecordingFileDto
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    private fun loopFor(
        root: Path,
        watchPeriod: Duration = Duration.ofDays(1),
    ) = WatchRecordsLoop(
        recordsWatcherProperties =
            RecordsWatcherProperties(
                folder = root,
                watchPeriod = watchPeriod,
                cleanupInterval = Duration.ofHours(1),
            ),
        recordingEntityHelper = recordingEntityHelper,
        recordingFileHelper = recordingFileHelper,
        clock = clock,
    )

    // --- registerAllDirs ---

    @Test
    fun `registerAllDirs registers root in-window dates hours and cameras`() {
        val root = Files.createTempDirectory("rad-registers")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            assertEquals(15, result.registered)
            assertEquals(canonicalRegisteredDirs(root), dirs.keys.toSet())
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs never enumerates recording files`() {
        val root = Files.createTempDirectory("rad-no-files")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            // 48 .mp4 files exist on disk. None of them may ever reach visitFile: the walk stops
            // at the camera level, and visitedFiles counts exactly what visitFile saw.
            assertEquals(17, result.visitedEntries)
            assertEquals(0, result.visitedFiles, "no recording file may be enumerated during registration")
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs prunes out-of-window date subtrees`() {
        val root = Files.createTempDirectory("rad-prunes")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            assertEquals(2, result.prunedSubtrees)
            assertTrue(
                dirs.keys.none { key -> CANONICAL_DATES_OUT_OF_WINDOW.any { key.toString().contains(it) } },
                "no directory under an out-of-window date may be registered",
            )
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs does not descend below the camera level`() {
        val root = Files.createTempDirectory("rad-camera-depth")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val nested = root.resolve("2026-05-23/00/cam1/nested")
            Files.createDirectories(nested)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            assertFalse(dirs.containsKey(nested))
            assertEquals(17, result.visitedEntries, "the camera directory's contents must not be enumerated")
            assertEquals(0, result.visitedFiles)
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs registers the root even though it carries no date`() {
        val root = Files.createTempDirectory("rad-root")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            loopFor(root).registerAllDirs(root, watchService, dirs)

            assertTrue(dirs.containsKey(root), "the root must stay watched so new date dirs are noticed")
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs handles a start below the root`() {
        val root = Files.createTempDirectory("rad-substart")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            // This is the runIteration call-site: a freshly created date directory.
            val result = loopFor(root).registerAllDirs(root.resolve("2026-05-23"), watchService, dirs)

            assertEquals(7, result.registered)
            assertEquals(0, result.prunedSubtrees)
            assertEquals(7, result.visitedEntries)
            assertEquals(0, result.visitedFiles)
            assertFalse(dirs.containsKey(root))
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs is idempotent`() {
        val root = Files.createTempDirectory("rad-idempotent")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()
            val loop = loopFor(root)

            loop.registerAllDirs(root, watchService, dirs)
            val second = loop.registerAllDirs(root, watchService, dirs)

            assertEquals(0, second.registered)
            assertEquals(2, second.prunedSubtrees)
            assertEquals(17, second.visitedEntries)
            assertEquals(0, second.visitedFiles)
            assertEquals(15, dirs.size)
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs skips an unreadable directory instead of aborting`() {
        val root = Files.createTempDirectory("rad-unreadable")
        val watchService = FileSystems.getDefault().newWatchService()
        val locked = root.resolve("2026-05-23/00")
        try {
            buildCanonicalTree(root)
            Files.setPosixFilePermissions(locked, emptySet<PosixFilePermission>())
            // Under root (typical CI containers) chmod 000 does not restrict access, the assumption
            // below is always false and the test silently skips — the visitFileFailed path has
            // automated coverage only on machines with a regular UID. Known and accepted.
            assumeTrue(
                runCatching { Files.newDirectoryStream(locked).use { it.iterator().hasNext() } }.isFailure,
                "chmod 000 does not restrict this user (running as root?) — cannot simulate an unreadable directory",
            )
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            // 15 expected minus the locked hour directory and its two cameras.
            assertEquals(12, result.registered)
            assertEquals(1, result.failed)
            // 12 registered + 2 pruned + 1 failed; the locked cameras are never reached.
            assertEquals(15, result.visitedEntries)
            assertFalse(dirs.containsKey(locked))
            assertTrue(dirs.containsKey(root.resolve("2026-05-23/01/cam1")))
            assertTrue(dirs.containsKey(root.resolve("2026-05-22/01/cam2")))
        } finally {
            runCatching {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
            }
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs visits but does not register a stray file at the date level`() {
        val root = Files.createTempDirectory("rad-stray")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val stray = root.resolve("2026-05-23/stray.txt")
            Files.createFile(stray)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            // A foreign file above the camera level is visited (that is unavoidable) but never
            // registered; visitedFiles counts exactly it, keeping the invariant observable.
            assertEquals(15, result.registered)
            assertEquals(18, result.visitedEntries)
            assertEquals(1, result.visitedFiles)
            assertFalse(dirs.containsKey(stray))
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs does not register a start below the camera level`() {
        val root = Files.createTempDirectory("rad-deep-start")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val nested = root.resolve("2026-05-23/00/cam1/nested")
            Files.createDirectories(nested)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            // runIteration passes freshly created directories as start. One below the camera level
            // must not acquire a watch key: the startup walk would never restore it after the
            // WatchService is recreated, so it would silently vanish.
            val result = loopFor(root).registerAllDirs(nested, watchService, dirs)

            assertEquals(0, result.registered)
            assertEquals(1, result.visitedEntries)
            assertTrue(dirs.isEmpty())
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs throws when the start does not exist`() {
        val root = Files.createTempDirectory("rad-missing-start")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            // A typo in FRIGATE_RECORDS_FOLDER or an unmounted NFS volume must stay retryable:
            // ensureWatchService treats the throw as a registration failure and backs off —
            // a silently "successful" empty registration would leave health stuck DOWN forever.
            assertThrows<NoSuchFileException> {
                loopFor(root).registerAllDirs(root.resolve("gone"), watchService, dirs)
            }
            assertTrue(dirs.isEmpty())
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs throws when the start is a symlink`() {
        val root = Files.createTempDirectory("rad-symlink-start")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val link = root.resolve("latest")
            Files.createSymbolicLink(link, root.resolve("2026-05-23"))
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            // The walk runs without FOLLOW_LINKS, so a symlinked start is classified as a FILE
            // and the traversal would end after one visit with nothing registered.
            assertThrows<NotDirectoryException> {
                loopFor(root).registerAllDirs(link, watchService, dirs)
            }
            assertTrue(dirs.isEmpty())
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs degrades to a full walk for a start outside the root`() {
        val root = Files.createTempDirectory("rad-outside-root")
        val outside = Files.createTempDirectory("rad-outside-tree")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            val leaf = outside.resolve("a/b/c/d")
            Files.createDirectories(leaf)
            Files.createFile(leaf.resolve("file.bin"))
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            // depthFromRoot == -1 for every path here: depth rules do not apply and the walk
            // visits everything — today's behaviour, deliberately preserved.
            val result = loopFor(root).registerAllDirs(outside, watchService, dirs)

            assertEquals(5, result.registered)
            assertEquals(6, result.visitedEntries)
            assertEquals(1, result.visitedFiles)
        } finally {
            watchService.close()
            outside.toFile().deleteRecursively()
            root.toFile().deleteRecursively()
        }
    }
}
