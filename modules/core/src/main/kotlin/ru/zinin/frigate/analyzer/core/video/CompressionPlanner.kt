package ru.zinin.frigate.analyzer.core.video

import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.CompressProperties
import ru.zinin.frigate.analyzer.core.config.properties.ExportProperties
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Chooses how to re-encode a video so that it fits into a byte budget.
 *
 * Pure arithmetic, no I/O: the caller probes the source ([VideoProbe]) and runs ffmpeg
 * (`VideoMergeHelper.compressVideo`).
 *
 * Budget: `targetBytes * 8 / 1000 / duration` kbit/s, minus [CONTAINER_RESERVE], minus the audio
 * track when there is one. Resolution: the largest of [HEIGHTS] (never above the source) whose
 * bits-per-pixel at that budget stays at or above [CompressProperties.minBitsPerPixel]; if none
 * qualifies, the smallest candidate.
 */
@Component
class CompressionPlanner(
    exportProperties: ExportProperties,
) {
    private val settings: CompressProperties = exportProperties.compress

    /** Plan for the first encode of [info] into [targetBytes]. */
    fun plan(
        info: VideoInfo,
        targetBytes: Long,
    ): CompressionPlan {
        require(info.durationSeconds > 0) { "durationSeconds must be positive, got ${info.durationSeconds}" }
        require(info.width > 0 && info.height > 0) { "frame size must be positive, got ${info.width}x${info.height}" }
        require(info.fps > 0) { "fps must be positive, got ${info.fps}" }
        require(targetBytes > 0) { "targetBytes must be positive, got $targetBytes" }

        val totalKbps = targetBytes.toDouble() * BITS_PER_BYTE / BITS_PER_KBIT / info.durationSeconds
        val usableKbps = totalKbps * (1 - CONTAINER_RESERVE)
        val audioKbps = if (info.hasAudio) AUDIO_BITRATE_KBPS else null
        val videoKbps = floor(usableKbps - (audioKbps ?: 0)).toInt()
        if (videoKbps <= 0) {
            throw VideoTooLargeException(
                "No bitrate budget for video: ${info.durationSeconds}s must fit into $targetBytes bytes",
            )
        }
        return buildPlan(info, sourceHeight = info.height, videoKbps = videoKbps, audioKbps = audioKbps)
    }

    /**
     * Plan for the retry after the first encode produced [actualBytes] instead of [targetBytes].
     * The retry re-encodes the first result, so that result's height is the new source height.
     */
    fun shrink(
        previous: CompressionPlan,
        info: VideoInfo,
        actualBytes: Long,
        targetBytes: Long,
    ): CompressionPlan {
        require(actualBytes > 0) { "actualBytes must be positive, got $actualBytes" }
        require(targetBytes > 0) { "targetBytes must be positive, got $targetBytes" }

        val videoKbps = floor(previous.videoMaxrateKbps * targetBytes.toDouble() / actualBytes * SHRINK_FACTOR).toInt()
        if (videoKbps <= 0) {
            throw VideoTooLargeException(
                "No bitrate budget for video after overshoot: $actualBytes bytes produced for a $targetBytes target",
            )
        }
        return buildPlan(
            info,
            sourceHeight = previous.scaleHeight ?: info.height,
            videoKbps = videoKbps,
            audioKbps = previous.audioBitrateKbps,
        )
    }

    /** Width of [info] scaled to [height], rounded to the nearest even number as `scale=-2:<h>` does. */
    internal fun scaledWidth(
        info: VideoInfo,
        height: Int,
    ): Int {
        val exact = info.width.toDouble() * height / info.height
        return maxOf(2, (exact / 2).roundToInt() * 2)
    }

    private fun buildPlan(
        info: VideoInfo,
        sourceHeight: Int,
        videoKbps: Int,
        audioKbps: Int?,
    ): CompressionPlan {
        val chosenHeight = chooseHeight(info, sourceHeight, videoKbps)
        return CompressionPlan(
            scaleHeight = chosenHeight.takeIf { it != sourceHeight },
            videoMaxrateKbps = videoKbps,
            audioBitrateKbps = audioKbps,
            crf = settings.crf,
            preset = settings.preset,
        )
    }

    private fun chooseHeight(
        info: VideoInfo,
        sourceHeight: Int,
        videoKbps: Int,
    ): Int {
        val candidates = (HEIGHTS + sourceHeight).filter { it <= sourceHeight }.distinct().sortedDescending()
        return candidates.firstOrNull { height ->
            val pixelsPerSecond = scaledWidth(info, height).toDouble() * height * info.fps
            videoKbps.toDouble() * BITS_PER_KBIT / pixelsPerSecond >= settings.minBitsPerPixel
        } ?: candidates.last()
    }

    companion object {
        val HEIGHTS = listOf(1080, 720, 540)
        const val AUDIO_BITRATE_KBPS = 64
        const val CONTAINER_RESERVE = 0.03
        const val SHRINK_FACTOR = 0.9
        private const val BITS_PER_BYTE = 8
        private const val BITS_PER_KBIT = 1000
    }
}
