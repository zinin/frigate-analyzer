package ru.zinin.frigate.analyzer.model.dto

import java.time.Instant
import java.util.UUID

data class DetectionDto(
    val id: UUID,
    val creationTimestamp: Instant,
    val recordingId: UUID,
    val detectionTimestamp: Instant,
    val frameIndex: Int,
    val model: String,
    val classId: Int,
    val className: String,
    val confidence: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)
