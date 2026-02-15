package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.absolutePathString

private val logger = KotlinLogging.logger {}

private const val POLL_PERIOD = 500L

private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun extractDateFromPath(path: Path): LocalDate? {
    for (i in path.nameCount - 1 downTo 0) {
        val name = path.getName(i).toString()
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
    watchPeriod: Duration,
    clock: Clock,
): Boolean {
    val date = extractDateFromPath(path) ?: return true
    val cutoff = LocalDate.now(clock).minusDays(watchPeriod.toDays())
    return !date.isBefore(cutoff)
}

@Component
class WatchRecordsTask(
    val recordsWatcherProperties: RecordsWatcherProperties,
    val recordingEntityHelper: RecordingEntityHelper,
    val recordingFileHelper: RecordingFileHelper,
) {
    private val stopped = AtomicBoolean(false)

    private val registeredDirs = ConcurrentHashMap<Path, WatchKey>()

    @Async
    fun run() {
        val folderPath = recordsWatcherProperties.folder
        logger.info { "Starting watch records in folder: $folderPath" }

        val watchService = FileSystems.getDefault().newWatchService()
        registerAllDirs(folderPath, watchService)

        logger.info { "Watch records started." }

        while (!stopped.get()) {
            val key = watchService.poll(POLL_PERIOD, TimeUnit.MILLISECONDS) ?: continue

            val dir = key.watchable() as Path

            for (event in key.pollEvents()) {
                val kind = event.kind()
                if (kind != ENTRY_CREATE) continue

                val ev = event as WatchEvent<Path>
                val fullPath = dir.resolve(ev.context())
                logger.info { "New file created: $fullPath" }

                if (Files.isDirectory(fullPath)) {
                    registerAllDirs(fullPath, watchService)
                } else {
                    val attrs = Files.readAttributes(fullPath, BasicFileAttributes::class.java)

                    val recordingFile = recordingFileHelper.parse(fullPath)

                    val recordingId =
                        runBlocking {
                            recordingEntityHelper.createRecording(
                                CreateRecordingRequest(
                                    fullPath.absolutePathString(),
                                    attrs.creationTime().toInstant(),
                                    recordingFile.camId,
                                    recordingFile.date,
                                    recordingFile.time,
                                    recordingFile.timestamp,
                                ),
                            )
                        }
                    logger.info { "Recording id: $recordingId" }
                }
            }

            if (!key.reset()) {
                registeredDirs.remove(dir)
            }
        }

        watchService.close()
        logger.info { "Finished watching records." }
    }

    fun shutdown() {
        stopped.set(true)
        logger.info { "Shutting down watch records..." }

        registeredDirs.values.forEach { it.cancel() }
        registeredDirs.clear()
    }

    private fun registerAllDirs(
        start: Path,
        watchService: WatchService,
    ) {
        Files
            .walk(start)
            .filter { Files.isDirectory(it) }
            .forEach { dir ->
                registeredDirs.computeIfAbsent(dir) {
                    val key = dir.register(watchService, ENTRY_CREATE)
                    logger.info { "Watching directory: $dir" }
                    key
                }
            }
    }
}
