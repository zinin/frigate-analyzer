package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.Path
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

// iter-2 CRITICAL-1: 3 fields per design §5.2.1 — eventsProcessed, eventFailures
// (for onPollCompleted), lastCleanupAt. WatchRecordsLoop.runIteration catches per-event
// exceptions internally and counts them in eventFailures (filled in Task 5 — runIteration body).
data class IterationResult(
    val eventsProcessed: Int,
    val eventFailures: Int,
    val lastCleanupAt: Instant,
)

@Component
class WatchRecordsLoop(
    private val recordsWatcherProperties: RecordsWatcherProperties,
    private val recordingEntityHelper: RecordingEntityHelper,
    private val recordingFileHelper: RecordingFileHelper,
    private val clock: Clock,
) {
    suspend fun runIteration(
        watchService: WatchService,
        registeredDirs: ConcurrentMap<Path, WatchKey>,
        lastCleanupAt: Instant,
    ): IterationResult {
        val key = watchService.poll(POLL_PERIOD_MS, TimeUnit.MILLISECONDS)
        // var, not val — Task 5 will increment inside the event loop.
        var processed = 0
        // TODO Task 5: process key.pollEvents()
        // TODO Task 5: cleanup if Duration.between(lastCleanupAt, now) >= cleanupInterval
        return IterationResult(eventsProcessed = processed, eventFailures = 0, lastCleanupAt = lastCleanupAt)
    }

    private companion object {
        const val POLL_PERIOD_MS: Long = 500L
    }
}
