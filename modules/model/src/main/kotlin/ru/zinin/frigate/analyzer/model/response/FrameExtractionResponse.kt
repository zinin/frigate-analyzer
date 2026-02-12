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
    val frameNumber: Int,
    val timestamp: Double,
    val imageBase64: String,
    val width: Int,
    val height: Int,
)
