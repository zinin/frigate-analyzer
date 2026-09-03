package ru.zinin.frigate.analyzer.core.video

/** What ffprobe reports about a video file; the input of [CompressionPlanner]. */
data class VideoInfo(
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean,
)
