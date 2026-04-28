package ru.zinin.frigate.analyzer.model.dto

/**
 * Aggregated bbox for one logical object inside a single recording.
 * Coordinates are pixel coordinates in the same coordinate space as DetectionEntity.x1..y2 (Float).
 */
data class RepresentativeBbox(
    val className: String,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)
