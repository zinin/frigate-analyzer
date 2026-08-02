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
        val failed: Int,
        val prunedSubtrees: Int,
        /** Every filesystem entry visited: directories + files (files are the point here, unlike registerAllDirs). */
        val visitedEntries: Int,
    )

    @Async
    fun run() {
        logger.info { "Starting first time scan task..." }

        CoroutineScope(Dispatchers.Default).launch {
            val result = scan()
            logger.info {
                "Finish first time scan task: indexed=${result.indexed}, failed=${result.failed}, " +
                    "pruned=${result.prunedSubtrees} date subtrees, visited=${result.visitedEntries} entries"
            }
        }
    }

    /**
     * Indexes every recording file inside the first-scan window.
     *
     * Split out of [run] so it can be driven from tests: [run] is a detached fire-and-forget
     * launch, the same split WatchRecordsLoop (logic) and WatchRecordsTask (supervision) already use.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun scan(): ScanResult {
        val root = recordsWatcherProperties.folder
        val cutoff = watchCutoff(recordsWatcherProperties.firstScanPeriod, clock)
        val files = mutableListOf<ScanFile>()
        var pruned = 0
        var visited = 0

        withContext(Dispatchers.IO) {
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

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        visited++
                        logger.warn { "First scan: skipping unreadable entry $file (${exc.message})" }
                        logger.debug(exc) { "First scan: failure details for $file" }
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

        logger.info {
            "First scan: ${files.size} files on or after $cutoff; pruned $pruned date subtrees"
        }

        val indexed = AtomicInteger()
        val failed = AtomicInteger()

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
                            logger.warn(e) { "First scan: skipping ${scanFile.path}" }
                            failed.incrementAndGet()
                            null
                        }
                    if (id != null) {
                        indexed.incrementAndGet()
                        emit(id)
                    }
                }
            }.catch { e ->
                // Flow.catch swallows CancellationException too — without this re-throw the
                // documented cancellability of scan() would be fiction.
                if (e is CancellationException) throw e
                logger.error(e) { "First scan aborted unexpectedly" }
            }
            // DEBUG, not INFO: a one-day window of three cameras is tens of thousands of files.
            .collect { id -> logger.debug { "First scan indexed recording $id" } }

        return ScanResult(indexed.get(), failed.get(), pruned, visited)
    }
}
