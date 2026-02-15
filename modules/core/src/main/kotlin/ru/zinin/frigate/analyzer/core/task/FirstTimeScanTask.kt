package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
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
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.absolutePathString
import kotlin.streams.asSequence

private val logger = KotlinLogging.logger {}

@Component
class FirstTimeScanTask(
    val recordsWatcherProperties: RecordsWatcherProperties,
    val recordingEntityHelper: RecordingEntityHelper,
    val recordingFileHelper: RecordingFileHelper,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Async
    fun run() {
        logger.info { "Starting first time scan task..." }

        CoroutineScope(Dispatchers.Default).launch {
            Files
                .walk(recordsWatcherProperties.folder)
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .asFlow()
                .flatMapMerge(concurrency = 8) { path ->
                    flow {
                        val attrs =
                            withContext(Dispatchers.IO) {
                                Files.readAttributes(path, BasicFileAttributes::class.java)
                            }

                        val recordingFile = recordingFileHelper.parse(path)

                        val id =
                            recordingEntityHelper.createRecording(
                                CreateRecordingRequest(
                                    path.absolutePathString(),
                                    attrs.creationTime().toInstant(),
                                    recordingFile.camId,
                                    recordingFile.date,
                                    recordingFile.time,
                                    recordingFile.timestamp,
                                ),
                            )
                        emit(id)
                    }
                }.catch { e -> logger.error(e) { "Error processing file" } }
                .collect { id -> logger.info { "Recording id: $id" } }

            logger.info { "Finish first time scan task." }
        }
    }
}
