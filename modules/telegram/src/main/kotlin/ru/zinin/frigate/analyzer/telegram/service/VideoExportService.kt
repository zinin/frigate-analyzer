package ru.zinin.frigate.analyzer.telegram.service

import ru.zinin.frigate.analyzer.model.dto.CameraRecordingCountDto
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface VideoExportService {
    suspend fun findCamerasWithRecordings(
        startInstant: Instant,
        endInstant: Instant,
    ): List<CameraRecordingCountDto>

    suspend fun exportVideo(
        startInstant: Instant,
        endInstant: Instant,
        camId: String,
        mode: ExportMode = ExportMode.ORIGINAL,
        onProgress: suspend (VideoExportProgress) -> Unit = {},
    ): Path

    suspend fun cleanupExportFile(path: Path)

    /**
     * Exports video by recording ID within ±[duration] range from recordTimestamp.
     * @param recordingId recording UUID from the database
     * @param duration one-side duration (default 1 minute, total range is 2 minutes)
     * @param onProgress progress callback
     * @return path to the exported video file
     * @throws IllegalArgumentException if the recording is not found
     * @throws IllegalStateException if the recording files are missing from disk
     */
    suspend fun exportByRecordingId(
        recordingId: UUID,
        duration: Duration = Duration.ofMinutes(1),
        onProgress: suspend (VideoExportProgress) -> Unit = {},
    ): Path
}
