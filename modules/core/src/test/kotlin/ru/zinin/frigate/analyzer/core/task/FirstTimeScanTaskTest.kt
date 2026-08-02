package ru.zinin.frigate.analyzer.core.task

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.model.dto.RecordingFileDto
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class FirstTimeScanTaskTest {
    private val recordingEntityHelper = mockk<RecordingEntityHelper>()
    private val recordingFileHelper = mockk<RecordingFileHelper>()
    private val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)

    private val sampleDto =
        RecordingFileDto(
            basePath = "/mnt/data/frigate/recordings",
            camId = "cam1",
            date = LocalDate.of(2026, 5, 23),
            time = LocalTime.of(0, 10, 0),
            timestamp = Instant.parse("2026-05-23T00:10:00Z"),
        )

    private fun taskFor(
        root: Path,
        firstScanPeriod: Duration = Duration.ofDays(1),
    ) = FirstTimeScanTask(
        recordsWatcherProperties =
            RecordsWatcherProperties(
                folder = root,
                watchPeriod = Duration.ofDays(1),
                firstScanPeriod = firstScanPeriod,
                cleanupInterval = Duration.ofHours(1),
            ),
        recordingEntityHelper = recordingEntityHelper,
        recordingFileHelper = recordingFileHelper,
        clock = clock,
    )

    @Test
    fun `scan indexes only files inside the first-scan window`() =
        runTest {
            val root = Files.createTempDirectory("fts-window")
            try {
                buildCanonicalTree(root)
                // CopyOnWriteArrayList: flatMapMerge runs the per-file body concurrently.
                val requests = CopyOnWriteArrayList<CreateRecordingRequest>()
                every { recordingFileHelper.parse(any()) } returns sampleDto
                coEvery { recordingEntityHelper.createRecording(capture(requests)) } returns UUID.randomUUID()

                val result = taskFor(root).scan()

                assertEquals(24, result.indexed)
                assertEquals(0, result.failed)
                assertEquals(2, result.prunedSubtrees)
                // 1 root + 4 dates + 4 hours + 8 cameras + 24 files — the appendix breakdown.
                assertEquals(41, result.visitedEntries)
                assertEquals(24, requests.size)
                assertTrue(
                    requests.none { req -> CANONICAL_DATES_OUT_OF_WINDOW.any { req.filePath.contains(it) } },
                    "no file under an out-of-window date may be indexed",
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `scan keeps going when one file fails`() =
        runTest {
            val root = Files.createTempDirectory("fts-failure")
            try {
                buildCanonicalTree(root)
                val poison = root.resolve("2026-05-23/00/cam1/00.20.mp4")
                every { recordingFileHelper.parse(any()) } answers {
                    if (firstArg<Path>() == poison) throw IllegalArgumentException("bogus filename")
                    sampleDto
                }
                coEvery { recordingEntityHelper.createRecording(any()) } returns UUID.randomUUID()

                val result = taskFor(root).scan()

                assertEquals(23, result.indexed)
                assertEquals(1, result.failed)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `scan with a P0D window indexes today only`() =
        runTest {
            val root = Files.createTempDirectory("fts-today")
            try {
                buildCanonicalTree(root)
                every { recordingFileHelper.parse(any()) } returns sampleDto
                coEvery { recordingEntityHelper.createRecording(any()) } returns UUID.randomUUID()

                val result = taskFor(root, Duration.ZERO).scan()

                assertEquals(12, result.indexed)
                assertEquals(3, result.prunedSubtrees)
                // 1 root + 4 dates + 2 hours + 4 cameras + 12 files.
                assertEquals(23, result.visitedEntries)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
