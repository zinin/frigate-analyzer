package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import java.time.Instant
import java.util.UUID

data class SavedProcessingResult(
    val recording: RecordingDto,
    val detections: List<DetectionEntity>,
)

interface RecordingEntityService {
    suspend fun createRecording(request: CreateRecordingRequest): UUID

    suspend fun findUnprocessedRecordings(limit: Int = 10): List<RecordingDto>

    suspend fun saveProcessingResult(request: SaveProcessingResultRequest): SavedProcessingResult

    suspend fun getRecording(id: UUID): RecordingDto?

    /** Последняя запись камеры, начавшаяся в `[from, before)`; `null` — такой нет. */
    suspend fun findPreviousSegment(
        camId: String,
        from: Instant,
        before: Instant,
    ): RecordingDto?

    /** Первая запись камеры, начавшаяся в `(after, until]`; `null` — такой нет. */
    suspend fun findNextSegment(
        camId: String,
        after: Instant,
        until: Instant,
    ): RecordingDto?

    suspend fun deleteRecording(id: UUID)

    suspend fun incrementProcessAttempts(id: UUID)

    suspend fun markProcessedWithError(
        id: UUID,
        errorMessage: String,
    )
}
