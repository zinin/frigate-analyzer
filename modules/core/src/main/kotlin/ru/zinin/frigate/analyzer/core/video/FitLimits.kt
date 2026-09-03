package ru.zinin.frigate.analyzer.core.video

/**
 * Size limits for one delivery channel.
 *
 * @property thresholdBytes files up to this size are sent as they are; above it they are
 *   re-encoded with this value as the byte budget
 * @property maxBytes hard acceptance limit for the re-encoded result
 */
data class FitLimits(
    val thresholdBytes: Long,
    val maxBytes: Long,
) {
    init {
        require(thresholdBytes in 1..maxBytes) { "thresholdBytes must be in 1..maxBytes, got $thresholdBytes / $maxBytes" }
    }

    companion object {
        /**
         * Telegram Bot API upload limit is documented as "50 MB"; the exact byte boundary is not
         * public, so the decimal reading is used as the stricter one. 45 MiB keeps the budget
         * below both readings.
         */
        val TELEGRAM = FitLimits(thresholdBytes = 45L * 1024 * 1024, maxBytes = 50_000_000L)
    }
}
