package ru.zinin.frigate.analyzer.core.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.helper.VideoMergeHelper
import ru.zinin.frigate.analyzer.model.dto.CameraRecordingCountDto
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class VideoExportServiceImpl(
    private val recordingRepository: RecordingEntityRepository,
    private val videoMergeHelper: VideoMergeHelper,
    private val tempFileHelper: TempFileHelper,
) : VideoExportService {
    override suspend fun findCamerasWithRecordings(
        startInstant: Instant,
        endInstant: Instant,
    ): List<CameraRecordingCountDto> = recordingRepository.findCamerasWithRecordings(startInstant, endInstant)

    override suspend fun exportVideo(
        startInstant: Instant,
        endInstant: Instant,
        camId: String,
    ): Path {
        val recordings = recordingRepository.findByCamIdAndInstantRange(camId, startInstant, endInstant)

        if (recordings.isEmpty()) {
            throw IllegalStateException("No recordings found for camId=$camId, range=$startInstant-$endInstant")
        }

        val existingFiles =
            recordings.mapNotNull { recording ->
                val path = recording.filePath?.let { Path.of(it) }
                if (path != null && withContext(Dispatchers.IO) { Files.exists(path) }) {
                    path
                } else {
                    logger.warn { "Recording file not found: ${recording.filePath} (id=${recording.id})" }
                    null
                }
            }

        if (existingFiles.isEmpty()) {
            throw IllegalStateException("All recording files are missing from disk")
        }

        logger.info { "Exporting ${existingFiles.size} recordings for camId=$camId, range=$startInstant-$endInstant" }

        var mergedFile = videoMergeHelper.mergeVideos(existingFiles)

        try {
            val fileSize = withContext(Dispatchers.IO) { Files.size(mergedFile) }
            if (fileSize > VideoMergeHelper.COMPRESS_THRESHOLD_BYTES) {
                logger.info { "Merged file is ${fileSize / 1024 / 1024}MB, compressing..." }
                val compressedFile = videoMergeHelper.compressVideo(mergedFile)
                tempFileHelper.deleteIfExists(mergedFile)
                mergedFile = compressedFile

                val compressedSize = withContext(Dispatchers.IO) { Files.size(mergedFile) }
                if (compressedSize > VideoMergeHelper.MAX_FILE_SIZE_BYTES) {
                    tempFileHelper.deleteIfExists(mergedFile)
                    throw IllegalStateException(
                        "Video too large even after compression: ${compressedSize / 1024 / 1024}MB",
                    )
                }
            }

            return mergedFile
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(mergedFile)
            throw e
        }
    }

    override suspend fun cleanupExportFile(path: Path) {
        tempFileHelper.deleteIfExists(path)
    }
}
