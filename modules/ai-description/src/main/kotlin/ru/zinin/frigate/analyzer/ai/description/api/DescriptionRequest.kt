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
        /**
         * Время кадра в секундах от начала отрезка, который видит модель; `null` — неизвестно.
         * Задача подписывает им кадр, чтобы модель знала, сколько прошло между кадрами.
         */
        val offsetSeconds: Double? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameImage) return false
            return frameIndex == other.frameIndex &&
                offsetSeconds == other.offsetSeconds &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * (31 * frameIndex + offsetSeconds.hashCode()) + bytes.contentHashCode()

        // Generated `toString` would print ByteArray identity hash (e.g. "bytes=[B@1a2b3c4d"),
        // which is useless in logs. Print length instead — actual bytes can't be usefully logged anyway.
        override fun toString(): String = "FrameImage(frameIndex=$frameIndex, offsetSeconds=$offsetSeconds, bytes=${bytes.size}B)"
    }
}
