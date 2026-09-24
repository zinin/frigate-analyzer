package ru.zinin.frigate.analyzer.ai.description.api

import java.util.Objects
import java.util.UUID

data class DescriptionRequest(
    val recordingId: UUID,
    val frames: List<FrameImage>,
    val language: String,
    val shortMaxLength: Int,
    val detailedMaxLength: Int,
    /** Раскадровка события; `null` — модель получает только [frames]. */
    val storyboard: Storyboard? = null,
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

    /**
     * Одна картинка-сетка: [tiles] кадров с шагом [stepSeconds] за [durationSeconds] секунд записи,
     * слева направо и сверху вниз. Все времена — секунды от начала этого отрезка; в них же
     * [FrameImage.offsetSeconds] полноразмерных кадров.
     */
    data class Storyboard(
        val image: ByteArray,
        val tiles: Int,
        val stepSeconds: Double,
        val durationSeconds: Double,
        val detections: List<DetectionMark>,
        /** Окно хотело захватить запись раньше начала отрезка, а её не оказалось. */
        val missingBefore: Boolean,
        /** Окно хотело захватить запись позже конца отрезка, а её не оказалось. */
        val missingAfter: Boolean,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Storyboard) return false
            return tiles == other.tiles &&
                stepSeconds == other.stepSeconds &&
                durationSeconds == other.durationSeconds &&
                detections == other.detections &&
                missingBefore == other.missingBefore &&
                missingAfter == other.missingAfter &&
                image.contentEquals(other.image)
        }

        override fun hashCode(): Int =
            31 * Objects.hash(tiles, stepSeconds, durationSeconds, detections, missingBefore, missingAfter) +
                image.contentHashCode()

        override fun toString(): String =
            "Storyboard(tiles=$tiles, stepSeconds=$stepSeconds, durationSeconds=$durationSeconds, " +
                "detections=$detections, missingBefore=$missingBefore, missingAfter=$missingAfter, image=${image.size}B)"
    }

    /** Что нашёл детектор на одном кадре: класс, лучший confidence этого класса на кадре и время кадра. */
    data class DetectionMark(
        val className: String,
        val confidence: Double,
        /** Секунды от начала отрезка; `null` — время кадра неизвестно. */
        val offsetSeconds: Double?,
    )
}
