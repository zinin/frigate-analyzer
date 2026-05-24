package ru.zinin.frigate.analyzer.core.task

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.Path
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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
}
