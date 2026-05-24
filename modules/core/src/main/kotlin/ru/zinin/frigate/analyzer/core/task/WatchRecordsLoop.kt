package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

private val logger = KotlinLogging.logger {}

// iter-2 CRITICAL-1: 3 fields per design §5.2.1 — eventsProcessed, eventFailures
// (for onPollCompleted), lastCleanupAt. WatchRecordsLoop.runIteration catches per-event
// exceptions internally and counts them in eventFailures.
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
        var processed = 0
        var eventFailures = 0
        if (key != null) {
            // key.watchable() returns Watchable; for keys produced by Path.register(...) it's always Path.
            // This is the WatchService contract — no ClassCastException is possible here.
            val dir = key.watchable() as Path
            try {
                for (event in key.pollEvents()) {
                    if (event.kind() != ENTRY_CREATE) continue

                    @Suppress("UNCHECKED_CAST")
                    val ev = event as WatchEvent<Path>
                    val fullPath = dir.resolve(ev.context())
                    logger.debug { "New file created: $fullPath" }

                    // iter-2 CRITICAL-1 / D2: per-event exception isolation. Generic Exception is counted
                    // in eventFailures so one bad file doesn't kill the whole batch. ClosedWatchServiceException
                    // and CancellationException are rethrown so the supervisor can react (recreate watcher /
                    // honor cancel). Note: if WatchService.poll() itself throws ClosedWatchServiceException
                    // (the more common case), it propagates from the outer call — supervisor handles it
                    // the same way.
                    try {
                        if (Files.isDirectory(fullPath)) {
                            val withinPeriod =
                                isWithinWatchPeriod(
                                    fullPath,
                                    recordsWatcherProperties.folder,
                                    recordsWatcherProperties.watchPeriod,
                                    clock,
                                )
                            if (withinPeriod) {
                                registerAllDirs(fullPath, watchService, registeredDirs)
                            } else {
                                logger.info { "Skipping old directory: $fullPath" }
                            }
                        } else {
                            val attrs = Files.readAttributes(fullPath, BasicFileAttributes::class.java)
                            val recordingFile = recordingFileHelper.parse(fullPath)
                            val recordingId =
                                recordingEntityHelper.createRecording(
                                    CreateRecordingRequest(
                                        filePath = fullPath.absolutePathString(),
                                        fileCreationTimestamp = attrs.creationTime().toInstant(),
                                        camId = recordingFile.camId,
                                        recordDate = recordingFile.date,
                                        recordTime = recordingFile.time,
                                        recordTimestamp = recordingFile.timestamp,
                                    ),
                                )
                            logger.info { "Recording id: $recordingId" }
                        }
                        processed++
                    } catch (e: ClosedWatchServiceException) {
                        throw e // bubble up — supervisor recreates WatchService
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Event processing failed for $fullPath; counted as failure" }
                        eventFailures++
                    }
                }
            } finally {
                if (!key.reset()) {
                    registeredDirs.remove(dir)
                }
            }
        }

        val now = Instant.now(clock)
        val newLastCleanup =
            if (Duration.between(lastCleanupAt, now) >= recordsWatcherProperties.cleanupInterval) {
                cleanupExpiredDirs(registeredDirs)
                now
            } else {
                lastCleanupAt
            }
        return IterationResult(eventsProcessed = processed, eventFailures = eventFailures, lastCleanupAt = newLastCleanup)
    }

    fun registerAllDirs(
        start: Path,
        watchService: WatchService,
        registeredDirs: ConcurrentMap<Path, WatchKey>,
    ): Int {
        var registered = 0
        var skipped = 0
        Files.walk(start).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .forEach { dir ->
                    if (isWithinWatchPeriod(dir, recordsWatcherProperties.folder, recordsWatcherProperties.watchPeriod, clock)) {
                        registeredDirs.computeIfAbsent(dir) {
                            val k = dir.register(watchService, ENTRY_CREATE)
                            registered++
                            k
                        }
                    } else {
                        skipped++
                    }
                }
        }
        logger.info { "Registered $registered directories, skipped $skipped old directories." }
        return registered
    }

    // Single-writer invariant: cleanupExpiredDirs() and event processing run on the same
    // dedicated dispatcher thread (Dispatchers.IO.limitedParallelism(1)) — ConcurrentHashMap
    // is used only so the HealthIndicator bean can safely read .size from another thread.
    private fun cleanupExpiredDirs(registeredDirs: ConcurrentMap<Path, WatchKey>) {
        var removed = 0
        registeredDirs.entries.removeIf { (dir, watchKey) ->
            if (!isWithinWatchPeriod(dir, recordsWatcherProperties.folder, recordsWatcherProperties.watchPeriod, clock)) {
                watchKey.cancel()
                removed++
                true
            } else {
                false
            }
        }
        if (removed > 0) {
            logger.info { "Cleanup: removed $removed expired watch keys. Active watches: ${registeredDirs.size}" }
        }
    }

    private companion object {
        const val POLL_PERIOD_MS: Long = 500L
    }
}

private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun extractDateFromPath(
    path: Path,
    rootFolder: Path,
): LocalDate? {
    val relativePath = if (path.startsWith(rootFolder)) rootFolder.relativize(path) else path
    for (i in relativePath.nameCount - 1 downTo 0) {
        val name = relativePath.getName(i).toString()
        if (DATE_PATTERN.matches(name)) {
            return try {
                LocalDate.parse(name)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
    return null
}

internal fun isWithinWatchPeriod(
    path: Path,
    rootFolder: Path,
    watchPeriod: Duration,
    clock: Clock,
): Boolean {
    val date = extractDateFromPath(path, rootFolder) ?: return true
    val cutoff = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(watchPeriod.toDays())
    return !date.isBefore(cutoff)
}
