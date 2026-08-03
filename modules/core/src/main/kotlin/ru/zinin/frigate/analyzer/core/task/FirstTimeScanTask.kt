package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString

private val logger = KotlinLogging.logger {}

private const val SCAN_CONCURRENCY = 8

/** After this many failures the per-entry WARNs collapse into one summary line. */
private const val FAILURE_LOG_LIMIT = 100

/**
 * Above this many days the window is a deliberate act, not the inherited default, and the operator
 * is warned what it costs. The walk materializes the whole file list before processing, so peak
 * memory scales with the file count in the window (~200 bytes per entry). There is deliberately no
 * upper `require` on `firstScanPeriod`: recovering a long outage is a legitimate reason to set a
 * wide window, and a hard cap would only swap one way of losing the gap for another.
 */
private const val WIDE_SCAN_WINDOW_DAYS = 7L

/**
 * A file collected by the walk together with its creation time. The walk already stat-ed every
 * entry to produce [BasicFileAttributes]; re-reading attributes per file in the processing flow
 * would be the same double-stat defect this task removes from registerAllDirs.
 */
private data class ScanFile(
    val path: Path,
    val createdAt: Instant,
)

@Component
class FirstTimeScanTask(
    private val recordsWatcherProperties: RecordsWatcherProperties,
    private val recordingEntityHelper: RecordingEntityHelper,
    private val recordingFileHelper: RecordingFileHelper,
    private val clock: Clock,
) {
    internal data class ScanResult(
        /** Successfully processed files: create-or-find, NOT "new rows" — a duplicate returns the existing id. */
        val indexed: Int,
        /**
         * Entries the scan gave up on, from BOTH phases: unreadable entries the walk skipped
         * (`visitFileFailed`) plus files whose `parse` or create-or-find threw. Two things are
         * deliberately outside it: an error reported to `postVisitDirectory` is logged only (same
         * as registerAllDirs), and a stray `CancellationException` re-thrown by the per-file
         * handler while the job itself is NOT cancelled cancels only that element's child
         * coroutine, so such a file lands in neither counter. Re-throwing is still right — the gap
         * is unreachable unless something raises `CancellationException` by hand.
         */
        val failed: Int,
        /**
         * Date subtrees skipped by `isPrunableDate` only — an unreadable entry is not counted
         * here. There is no `CAMERA_DEPTH` stop in this walk: the files below a camera are the
         * point.
         */
        val prunedSubtrees: Int,
        /** Every filesystem entry visited: directories + files (files are the point here, unlike registerAllDirs). */
        val visitedEntries: Int,
    )

    @Async
    fun run() {
        logger.info { "Starting first time scan task..." }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = scan()
                logger.info {
                    "Finish first time scan task: indexed=${result.indexed}, failed=${result.failed}, " +
                        "pruned=${result.prunedSubtrees} date subtrees, visited=${result.visitedEntries} entries"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The scope carries no CoroutineExceptionHandler, and the flow's `.catch` covers
                // only the second half of scan(): without this, anything thrown by the walk phase
                // would reach the JVM default handler on stderr instead of Log4j2, and the task
                // would finish without ever reporting its own outcome.
                logger.error(e) { "First time scan task failed" }
            }
        }
    }

    /**
     * Indexes every recording file inside the first-scan window.
     *
     * Split out of [run] so it can be driven from tests: [run] is a detached fire-and-forget
     * launch, the same split WatchRecordsLoop (logic) and WatchRecordsTask (supervision) already use.
     *
     * Cancellation takes effect from the FLOW phase onward. `Files.walkFileTree` offers no
     * cancellation check in any visitor callback, so a walk already in flight runs to completion
     * before the job notices; honouring cancellation there would mean an `ensureActive()` inside a
     * non-suspend `preVisitDirectory`, which costs more than it buys for a phase nothing cancels
     * today.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun scan(): ScanResult {
        val root = recordsWatcherProperties.folder
        val cutoff = watchCutoff(recordsWatcherProperties.firstScanPeriod, clock)
        val files = mutableListOf<ScanFile>()
        val indexed = AtomicInteger()
        val failed = AtomicInteger()
        var pruned = 0
        var visited = 0

        val windowDays = recordsWatcherProperties.firstScanPeriod.toDays()
        if (windowDays > WIDE_SCAN_WINDOW_DAYS) {
            logger.warn {
                "First scan window is FIRST_SCAN_PERIOD=P${windowDays}D (${windowDays + 1} UTC dates). " +
                    "The whole file list is held in memory before processing, so peak memory grows " +
                    "with the file count in that window — check the sizing note in .env.example"
            }
        }

        withContext(Dispatchers.IO) {
            // Error policy, deliberately laxer than registerAllDirs' strict root. There a failing
            // START is rethrown so the supervisor retries with backoff; scan() is a one-off with no
            // supervisor, so every failure is soft and the walk always finishes. A MISSING root
            // produces one WARN and `indexed=0` here, in visitFileFailed. A root that is a plain
            // file or a symlink (no FOLLOW_LINKS, so it arrives at visitFile) is dropped there as a
            // non-`.mp4` and would leave no trace at all — it is caught after the walk instead, by
            // the "found nothing" check, which also covers the shape neither callback can see: an
            // empty directory where the recordings volume should have been mounted.
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        visited++
                        // Same fail-closed prune as registerAllDirs. CAMERA_DEPTH deliberately does
                        // NOT apply here: the files below a camera directory are the point.
                        return if (isPrunableDate(dir, root, cutoff)) {
                            pruned++
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        visited++
                        // Attributes come from the walk itself — no per-file re-stat. Only real
                        // .mp4 files are collected: foreign files (thumbnails, tmp) are skipped
                        // silently instead of inflating `failed` via a doomed parse().
                        if (attrs.isRegularFile && file.fileName.toString().endsWith(".mp4")) {
                            files += ScanFile(file, attrs.creationTime().toInstant())
                        }
                        return FileVisitResult.CONTINUE
                    }

                    // Counted, not just logged: an unreadable camera directory takes its files out
                    // of `indexed` with nothing else to show for it, and a summary reading
                    // `failed=0` while whole subtrees were skipped is the very misleading-success
                    // shape this task exists to remove.
                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        visited++
                        if (failed.incrementAndGet() <= FAILURE_LOG_LIMIT) {
                            logger.warn { "First scan: skipping unreadable entry $file (${exc.message})" }
                            logger.debug(exc) { "First scan: failure details for $file" }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        if (exc != null) {
                            logger.warn { "First scan: error after visiting $dir (${exc.message})" }
                            logger.debug(exc) { "First scan: failure details for $dir" }
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        // The walk saw nothing but the start entry itself. Every silent-nothing shape collapses
        // here: a root that is a plain file or a symlink (no FOLLOW_LINKS, so it reaches visitFile
        // and is dropped as a non-.mp4), and — the one actually reachable on this deployment — a
        // bind mount whose host path was missing, which Docker materializes as an EMPTY real
        // directory. All three otherwise finish with `indexed=0, failed=0` and no warning at all,
        // which is indistinguishable from "the window held no recordings". It matters because the
        // scan is now the ONLY way to recover a downtime gap: an operator following the recovery
        // procedure would revert the flags believing the backfill ran, and lose the window.
        // `visited <= 1` cannot fire on a legitimately empty window — dates pruned by date leave
        // `pruned > 0` and `visited > 1`.
        if (visited <= 1 && files.isEmpty() && failed.get() == 0) {
            logger.warn {
                "First scan found nothing under $root — not even a date directory. " +
                    "Check FRIGATE_RECORDS_FOLDER and that the recordings volume is mounted"
            }
        }

        logger.info {
            "First scan: ${files.size} files on or after $cutoff; pruned $pruned date subtrees, " +
                "${failed.get()} unreadable entries"
        }

        files
            .asFlow()
            .flatMapMerge(concurrency = SCAN_CONCURRENCY) { scanFile ->
                flow {
                    // Per-file isolation. The previous shape put `.catch {}` on the outer chain,
                    // which TERMINATES the flow: the first failing file (unparseable name, a file
                    // Frigate deleted mid-walk, a raced IllegalStateException) silently killed the
                    // whole scan. A duplicate file_path does NOT fail: createRecording is
                    // create-or-find and returns the existing id with a WARN.
                    val id: UUID? =
                        try {
                            val recordingFile = recordingFileHelper.parse(scanFile.path)
                            recordingEntityHelper.createRecording(
                                CreateRecordingRequest(
                                    filePath = scanFile.path.absolutePathString(),
                                    fileCreationTimestamp = scanFile.createdAt,
                                    camId = recordingFile.camId,
                                    recordDate = recordingFile.date,
                                    recordTime = recordingFile.time,
                                    recordTimestamp = recordingFile.timestamp,
                                ),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Capped exactly like registerAllDirs. createRecording is inside this
                            // try, so a DB outage during a backfill fails EVERY file in the window
                            // — an uncapped stack trace per file would be ~52k of them in one burst.
                            if (failed.incrementAndGet() <= FAILURE_LOG_LIMIT) {
                                logger.warn { "First scan: skipping ${scanFile.path} (${e.message})" }
                                logger.debug(e) { "First scan: failure details for ${scanFile.path}" }
                            }
                            null
                        }
                    if (id != null) {
                        indexed.incrementAndGet()
                        emit(id)
                    }
                }
            }.catch { e ->
                // NOT a guard against the flow's own cancellation: Flow.catch already re-throws
                // when the exception is the one cancelling the flow (coroutines 1.11.0,
                // Errors.kt:167 — isSameExceptionAs(fromDownstream) || isCancellationCause(ctx)),
                // and its kdoc states it "does not catch exceptions that are thrown to cancel the
                // flow", so a genuine cancellation never reaches this action at all. Nor does a
                // stray CancellationException from the per-file handler: flatMapMerge runs each
                // inner flow as a child, and JobSupport.childCancelled absorbs it without
                // cancelling the producer (the mechanism ScanResult.failed describes). The
                // re-throw stays as the standard defensive shape of a `.catch` action — it costs
                // one line and can never turn a cancellation into "First scan aborted".
                if (e is CancellationException) throw e
                logger.error(e) { "First scan aborted unexpectedly" }
            }
            // DEBUG, not INFO: a one-day window of three cameras is tens of thousands of files.
            .collect { id -> logger.debug { "First scan indexed recording $id" } }

        val failedTotal = failed.get()
        // After BOTH phases, not just the walk: `failed` spans them, and the burst this cap exists
        // for (a DB outage failing every file) happens in the flow phase.
        if (failedTotal > FAILURE_LOG_LIMIT) {
            logger.warn { "First scan: ${failedTotal - FAILURE_LOG_LIMIT} more failures suppressed" }
        }

        // Named for the same reason as RegistrationResult: adjacent Ints swap silently.
        return ScanResult(
            indexed = indexed.get(),
            failed = failedTotal,
            prunedSubtrees = pruned,
            visitedEntries = visited,
        )
    }
}
