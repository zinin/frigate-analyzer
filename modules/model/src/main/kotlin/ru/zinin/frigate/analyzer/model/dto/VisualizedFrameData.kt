package ru.zinin.frigate.analyzer.model.dto

data class VisualizedFrameData(
    val frameIndex: Int,
    val visualizedBytes: ByteArray,
    val detectionsCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualizedFrameData) return false
        return frameIndex == other.frameIndex && detectionsCount == other.detectionsCount
    }

    override fun hashCode(): Int = 31 * frameIndex + detectionsCount

    override fun toString(): String =
        "VisualizedFrameData(frameIndex=$frameIndex, detectionsCount=$detectionsCount, size=${visualizedBytes.size})"
}
