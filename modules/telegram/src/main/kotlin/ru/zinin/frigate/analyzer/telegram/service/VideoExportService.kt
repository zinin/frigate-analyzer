package ru.zinin.frigate.analyzer.telegram.service

import ru.zinin.frigate.analyzer.model.dto.CameraRecordingCountDto
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime

interface VideoExportService {
    suspend fun findCamerasWithRecordings(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
    ): List<CameraRecordingCountDto>

    suspend fun exportVideo(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        camId: String,
    ): Path

    suspend fun cleanupExportFile(path: Path)
}
