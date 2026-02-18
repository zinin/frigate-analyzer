package ru.zinin.frigate.analyzer.telegram.service

import ru.zinin.frigate.analyzer.model.dto.CameraRecordingCountDto
import java.nio.file.Path
import java.time.Instant

interface VideoExportService {
    suspend fun findCamerasWithRecordings(
        startInstant: Instant,
        endInstant: Instant,
    ): List<CameraRecordingCountDto>

    suspend fun exportVideo(
        startInstant: Instant,
        endInstant: Instant,
        camId: String,
    ): Path

    suspend fun cleanupExportFile(path: Path)
}
