package ru.zinin.frigate.analyzer.core.video

/**
 * ffmpeg parameters for one re-encode.
 *
 * @property scaleHeight target frame height for `scale=-2:<h>`, or null to keep the source size
 * @property videoMaxrateKbps `-maxrate` in kbit/s; `-bufsize` is twice this value
 * @property audioBitrateKbps AAC bitrate in kbit/s, or null to drop audio (`-an`)
 */
data class CompressionPlan(
    val scaleHeight: Int?,
    val videoMaxrateKbps: Int,
    val audioBitrateKbps: Int?,
    val crf: Int,
    val preset: String,
)
