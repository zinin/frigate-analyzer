package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import java.util.UUID

interface RecordingEntityService {
    suspend fun createRecording(request: CreateRecordingRequest): UUID

    suspend fun findUnprocessedRecordings(limit: Int = 10): List<RecordingDto>

    suspend fun saveProcessingResult(request: SaveProcessingResultRequest)

    suspend fun getRecording(id: UUID): RecordingDto?

    suspend fun deleteRecording(id: UUID)

    suspend fun incrementProcessAttempts(id: UUID)

    suspend fun markProcessedWithError(
        id: UUID,
        errorMessage: String,
    )
}
