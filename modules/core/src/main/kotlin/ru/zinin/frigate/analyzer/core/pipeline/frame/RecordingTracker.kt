package ru.zinin.frigate.analyzer.core.pipeline.frame

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class RecordingTracker {
    private val recordings = ConcurrentHashMap<UUID, RecordingState>()

    /**
     * Registers a recording for progress tracking
     * @param recordId recording ID
     * @param frames list of recording frames (must not be empty)
     */
    fun registerRecording(
        recordId: UUID,
        frames: List<FrameData>,
    ) {
        require(frames.isNotEmpty()) { "frames must not be empty" }

        val state = RecordingState(recordId, frames)
        recordings[recordId] = state
        logger.debug { "Registered recording $recordId with ${frames.size} frames" }
    }

    /**
     * Marks a frame as successfully processed.
     * @return true if this is the last frame and the current thread should finalize the recording
     */
    fun markCompleted(
        recordId: UUID,
        frameIndex: Int,
        response: DetectResponse,
    ): Boolean {
        val state = getStateOrThrow(recordId)
        val shouldFinalize = state.markCompleted(frameIndex, response)

        logger.debug {
            "Recording $recordId: completed ${state.getCompletedCount()}/${state.totalFrames}, " +
                "pending ${state.getPendingCount()}"
        }

        return shouldFinalize
    }

    /**
     * Marks a frame as failed.
     * Safe to call even if the frame was already processed via markCompleted.
     * @return true if this is the last frame and the current thread should finalize the recording
     */
    fun markFailed(
        recordId: UUID,
        frameIndex: Int,
    ): Boolean {
        val state = getStateOrThrow(recordId)

        // If the frame is no longer pending (already processed or failed) — do nothing
        if (!state.isPending(frameIndex)) {
            logger.debug { "Frame $frameIndex already processed, skipping markFailed" }
            return false
        }

        val shouldFinalize = state.markFailed(frameIndex)

        logger.debug {
            "Recording $recordId: failed frame $frameIndex, " +
                "completed ${state.getCompletedCount()}/${state.totalFrames}, " +
                "pending ${state.getPendingCount()}"
        }

        return shouldFinalize
    }

    /**
     * Returns the recording state
     */
    fun getState(recordId: UUID): RecordingState? = recordings[recordId]

    /**
     * Removes a recording from tracking and returns its state
     */
    fun removeRecording(recordId: UUID): RecordingState? {
        val state = recordings.remove(recordId)
        if (state != null) {
            logger.debug { "Removed recording $recordId from tracker" }
        }
        return state
    }

    /**
     * Checks whether a recording is registered
     */
    fun isRegistered(recordId: UUID): Boolean = recordings.containsKey(recordId)

    /**
     * Number of recordings currently being processed
     */
    fun getActiveRecordingsCount(): Int = recordings.size

    private fun getStateOrThrow(recordId: UUID): RecordingState =
        recordings[recordId]
            ?: throw IllegalStateException("Recording $recordId not registered in tracker")
}
