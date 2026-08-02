package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

private val logger = KotlinLogging.logger {}

/** After this many unreadable entries the per-entry WARNs collapse into one summary line. */
private const val FAILURE_LOG_LIMIT = 100

// iter-2 CRITICAL-1: 3 fields per design §5.2.1 — eventsProcessed, eventFailures
// (for onPollCompleted), lastCleanupAt. WatchRecordsLoop.runIteration catches per-event
// exceptions internally and counts them in eventFailures.
data class IterationResult(
    val eventsProcessed: Int,
    val eventFailures: Int,
    val lastCleanupAt: Instant,
)

/**
 * Outcome of one [WatchRecordsLoop.registerAllDirs] traversal.
 *
 * [visitedFiles] is the load-bearing number: it counts entries delivered to `visitFile` — files
 * and symlinks ABOVE the camera level. On Frigate's tree it is 0 on every call (first walk,
 * re-walk over a populated map, runIteration sub-walks alike): the walk never descends below
 * [CAMERA_DEPTH], so no `.mp4` is ever enumerated — which is exactly the regression this
 * traversal exists to prevent, observable straight from the log line.
 *
 * [registered] counts NEW insertions only; a repeat walk over already-registered directories
 * reports 0 while still visiting them, so no arithmetic identity ties it to [visitedEntries].
 *
 * [failed] counts unreadable entries the walk skipped ([java.nio.file.SimpleFileVisitor]'s
 * visitFileFailed). Non-zero means partial blindness until the next full re-registration —
 * an accepted, observable degradation (it is printed in the log line). A failure of the START
 * itself is not counted here: it is rethrown so the supervisor retries with backoff.
 */
data class RegistrationResult(
    val registered: Int,
    val prunedSubtrees: Int,
    val visitedEntries: Int,
    val visitedFiles: Int,
    val failed: Int,
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
    ): RegistrationResult {
        val root = recordsWatcherProperties.folder
        // Computed once for the whole walk: a traversal that crosses midnight would otherwise
        // apply two different cutoffs to different branches of the same tree. The window check is
        // deliberately duplicated with runIteration's pre-check — registerAllDirs must stay
        // correct for ANY start, whoever calls it.
        val cutoff = watchCutoff(recordsWatcherProperties.watchPeriod, clock)
        var registered = 0
        var pruned = 0
        var visited = 0
        var visitedFiles = 0
        var failed = 0
        var misplacedDateReported = false
        val startedAt = System.nanoTime()

        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    visited++
                    // Nothing below a camera directory ever holds a watch key, from EITHER call
                    // path: runIteration may pass a start deeper than CAMERA_DEPTH, and a key
                    // taken there would silently vanish after the WatchService is recreated —
                    // the startup walk would never restore it.
                    if (depthFromRoot(dir, root) > CAMERA_DEPTH) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    // The date check runs at EVERY level, not just at depth 1: it is pure string
                    // work, and that keeps the PRUNE independent of where dates actually sit.
                    // Descent is what depth controls — and with it camera registration, which is
                    // why the misplaced-root detector below exists.
                    if (isPrunableDate(dir, root, cutoff)) {
                        pruned++
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (!misplacedDateReported && isDateAtUnexpectedDepth(dir, root)) {
                        misplacedDateReported = true
                        logger.warn {
                            "Registration: date directory at unexpected depth: $dir — " +
                                "check FRIGATE_RECORDS_FOLDER (it probably points one level above the recordings root)"
                        }
                    }
                    registeredDirs.computeIfAbsent(dir) {
                        val k = dir.register(watchService, ENTRY_CREATE)
                        registered++
                        k
                    }
                    return if (depthFromRoot(dir, root) >= CAMERA_DEPTH) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // A start that is a plain file or a symlink (the walk runs without
                    // FOLLOW_LINKS, so a symlinked root is classified as a FILE): an empty
                    // "successful" registration would report health DOWN forever with no retry.
                    if (file == start) throw NotDirectoryException(start.toString())
                    visited++
                    visitedFiles++
                    return FileVisitResult.CONTINUE
                }

                // SimpleFileVisitor rethrows by default, which would abort the whole registration
                // because of a single unreadable directory. The walk opens a directory BEFORE
                // preVisitDirectory, so an unreadable one arrives here, not there. Message-only
                // WARN: on a mass failure (unmounted subtree) a stack trace per entry floods the
                // log; the full trace is one DEBUG switch away.
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    // A failure of the START itself (missing or unreadable root) must stay fatal
                    // and retryable, exactly like Files.walk today: ensureWatchService catches,
                    // calls onRegistrationFailure and retries with backoff.
                    if (file == start) throw exc
                    visited++
                    failed++
                    if (failed <= FAILURE_LOG_LIMIT) {
                        logger.warn { "Registration: skipping unreadable entry $file (${exc.message})" }
                        logger.debug(exc) { "Registration: failure details for $file" }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) {
                        logger.warn { "Registration: error after visiting $dir (${exc.message})" }
                        logger.debug(exc) { "Registration: failure details for $dir" }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )

        if (failed > FAILURE_LOG_LIMIT) {
            logger.warn { "Registration: ${failed - FAILURE_LOG_LIMIT} more unreadable entries suppressed" }
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.info {
            "Registered $registered dirs, pruned $pruned date subtrees, " +
                "visited $visited entries ($visitedFiles files, $failed failed) in ${elapsedMs}ms"
        }
        return RegistrationResult(registered, pruned, visited, visitedFiles, failed)
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
