package ru.zinin.frigate.analyzer.core.pipeline.frame

import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recording processing state.
 * Tracks progress through frames.
 */
class RecordingState(
    val recordId: UUID,
    framesList: List<FrameData>,
) {
    /** All frames of the recording */
    private val frames =
        ConcurrentHashMap<Int, FrameData>().apply {
            framesList.forEach { put(it.frameIndex, it) }
        }

    /** Indices of frames awaiting processing */
    private val pendingIndices =
        ConcurrentHashMap.newKeySet<Int>().apply {
            addAll(framesList.map { it.frameIndex })
        }

    /** Finalization flag — ensures single execution */
    private val finalized = AtomicBoolean(false)

    /** Total number of frames */
    val totalFrames: Int = framesList.size

    /**
     * Marks a frame as successfully processed.
     * @return true if this was the last frame and the current thread should finalize the recording
     */
    fun markCompleted(
        frameIndex: Int,
        response: DetectResponse,
    ): Boolean {
        if (pendingIndices.remove(frameIndex)) {
            frames.computeIfPresent(frameIndex) { _, frame ->
                frame.copy(detectResponse = response)
            }
        }
        return tryAcquireFinalization()
    }

    /**
     * Marks a frame as failed (without result).
     * @return true if this was the last frame and the current thread should finalize the recording
     */
    fun markFailed(frameIndex: Int): Boolean {
        pendingIndices.remove(frameIndex)
        return tryAcquireFinalization()
    }

    /**
     * Checks whether a frame is pending processing
     */
    fun isPending(frameIndex: Int): Boolean = pendingIndices.contains(frameIndex)

    /**
     * Returns all successfully processed frames (with detectResponse)
     */
    fun getFrames(): List<FrameData> = frames.values.filter { it.detectResponse != null }

    /**
     * Number of successfully processed frames
     */
    fun getCompletedCount(): Int = frames.values.count { it.detectResponse != null }

    /**
     * Number of pending frames
     */
    fun getPendingCount(): Int = pendingIndices.size

    /**
     * Number of failed frames (totalFrames - completed - pending)
     */
    fun getFailedCount(): Int = totalFrames - getCompletedCount() - pendingIndices.size

    /**
     * Checks for completion and atomically acquires the right to finalize.
     */
    private fun tryAcquireFinalization(): Boolean {
        if (pendingIndices.isEmpty()) {
            return finalized.compareAndSet(false, true)
        }
        return false
    }
}
