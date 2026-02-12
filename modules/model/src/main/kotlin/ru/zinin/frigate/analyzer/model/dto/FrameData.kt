package ru.zinin.frigate.analyzer.model.dto

import ru.zinin.frigate.analyzer.model.response.DetectResponse
import java.util.UUID

data class FrameData(
    val recordId: UUID,
    val frameIndex: Int,
    val frameBytes: ByteArray,
    val detectResponse: DetectResponse? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameData) return false
        return recordId == other.recordId && frameIndex == other.frameIndex
    }

    override fun hashCode(): Int = 31 * recordId.hashCode() + frameIndex

    override fun toString(): String = "FrameData(recordId=$recordId, frameIndex=$frameIndex, size=${frameBytes.size})"
}
