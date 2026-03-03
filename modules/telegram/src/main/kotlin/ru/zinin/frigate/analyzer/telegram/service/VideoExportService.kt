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
     * Экспортирует видео по ID записи с диапазоном ±duration от recordTimestamp.
     * @param recordingId UUID записи из БД
     * @param duration длительность в одну сторону (по умолчанию 1 минута, итого 2 мин)
     * @param onProgress колбэк прогресса
     * @return путь к экспортированному видео
     * @throws IllegalArgumentException если запись не найдена
     * @throws IllegalStateException если файлы записи отсутствуют
     */
    suspend fun exportByRecordingId(
        recordingId: UUID,
        duration: Duration = Duration.ofMinutes(1),
        onProgress: suspend (VideoExportProgress) -> Unit = {},
    ): Path
}
