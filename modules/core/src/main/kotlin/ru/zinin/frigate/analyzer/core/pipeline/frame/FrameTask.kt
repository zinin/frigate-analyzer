package ru.zinin.frigate.analyzer.core.pipeline.frame

import java.util.UUID

data class FrameTask(
    val recordId: UUID,
    val frameIndex: Int,
    val frameBytes: ByteArray,
    /** Кадр отличается от первого кадра дневной записи — анализируется сразу good-model. */
    val changed: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameTask) return false
        return recordId == other.recordId && frameIndex == other.frameIndex
    }

    override fun hashCode(): Int = 31 * recordId.hashCode() + frameIndex

    override fun toString(): String = "FrameTask(recordId=$recordId, frameIndex=$frameIndex, size=${frameBytes.size}, changed=$changed)"
}
