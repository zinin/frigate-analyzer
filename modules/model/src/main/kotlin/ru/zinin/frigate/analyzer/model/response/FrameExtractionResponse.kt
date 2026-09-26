package ru.zinin.frigate.analyzer.model.response

data class FrameExtractionResponse(
    val success: Boolean,
    val videoDuration: Double,
    val videoResolution: List<Int>,
    val framesExtracted: Int,
    val frames: List<ExtractedFrameData>,
    val processingTimeMs: Long,
)

data class ExtractedFrameData(
    /** Number of the frame inside the recording, from 0. */
    val frameNumber: Int,
    /** Presentation timestamp of the frame, seconds from the start of the recording. */
    val timestamp: Double,
    val imageBase64: String,
    val width: Int,
    val height: Int,
    /**
     * Why the server picked this frame: `first`, `grid` or `motion`. Defaulted because a
     * vision-api older than 3.0 does not send it at all.
     */
    val reason: String = "unknown",
)
