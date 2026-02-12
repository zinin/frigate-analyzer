package ru.zinin.frigate.analyzer.model.response

data class DetectResponse(
    val detections: List<Detection>,
    val processingTime: Long,
    val imageSize: ImageSize,
    val model: String,
)

data class ImageSize(
    val width: Int,
    val height: Int,
)

data class Detection(
    val classId: Int,
    val className: String,
    val confidence: Double,
    val bbox: BBox,
)

data class BBox(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
)
