package ru.zinin.frigate.analyzer.service.helper

import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import java.util.UUID

@Component
class RecordingEntityHelper(
    private val recordingEntityService: RecordingEntityService,
) {
    suspend fun createRecording(
        request: CreateRecordingRequest,
        maxAttempts: Int = 3,
    ): UUID {
        repeat(maxAttempts) { attempt ->
            try {
                return recordingEntityService.createRecording(request)
            } catch (e: DuplicateKeyException) {
                if (attempt == maxAttempts - 1) {
                    throw IllegalStateException(
                        "Failed to create or find Recording after $maxAttempts attempts",
                        e,
                    )
                }
            }
        }

        throw IllegalStateException("Something went wrong when creating recording for $request")
    }
}
