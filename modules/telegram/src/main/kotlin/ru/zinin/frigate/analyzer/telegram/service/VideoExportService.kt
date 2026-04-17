package ru.zinin.frigate.analyzer.telegram.service

import ru.zinin.frigate.analyzer.model.dto.CameraRecordingCountDto
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
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
        onJobSubmitted: suspend (CancellableJob) -> Unit = {},
    ): Path

    suspend fun cleanupExportFile(path: Path)

    /**
     * Exports video by recording ID within ±[duration] range from recordTimestamp.
     *
     * @param recordingId recording UUID from the database
     * @param duration one-side duration (default 1 minute, total range is 2 minutes)
     * @param onProgress progress callback
     * @param onJobSubmitted callback invoked once (only for `mode == ANNOTATED`) as soon as the
     *   vision server has accepted the annotation job, delivering a handle that cancels the job.
     * @return path to the exported video file
     * @throws IllegalArgumentException if the recording is not found or duration is negative
     * @throws IllegalStateException if the recording has no camId or recordTimestamp
     */
    suspend fun exportByRecordingId(
        recordingId: UUID,
        duration: Duration = Duration.ofMinutes(1),
        mode: ExportMode = ExportMode.ORIGINAL,
        onProgress: suspend (VideoExportProgress) -> Unit = {},
        onJobSubmitted: suspend (CancellableJob) -> Unit = {},
    ): Path
}
