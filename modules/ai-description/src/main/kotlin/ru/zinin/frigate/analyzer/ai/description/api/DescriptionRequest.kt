package ru.zinin.frigate.analyzer.ai.description.api

import java.util.UUID

data class DescriptionRequest(
    val recordingId: UUID,
    val frames: List<FrameImage>,
    val language: String,
    val shortMaxLength: Int,
    val detailedMaxLength: Int,
) {
    data class FrameImage(
        val frameIndex: Int,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameImage) return false
            return frameIndex == other.frameIndex && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * frameIndex + bytes.contentHashCode()
    }
}
