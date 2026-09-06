package ru.zinin.frigate.analyzer.core.judge

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RecordingBlock(
    val cam: String,
    val time: String,
    val zone: String,
    val processingLagSeconds: Long?,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FrameDetectionBlock(
    @JsonProperty("class") val className: String,
    val confidence: Double,
    val bbox: List<Int>,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FrameBlock(
    val index: Int,
    val width: Int,
    val height: Int,
    val detections: List<FrameDetectionBlock>,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class StaticBlock(
    val recordings: Long,
    val days: Long,
    val firstSeen: String?,
    val lastSeen: String?,
    val recordingsInWindow: Long,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ObjectBlock(
    @JsonProperty("class") val className: String,
    val confidence: Double,
    val bbox: List<Int>,
    val framesSeen: Int,
    val static: Any?,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TrackerBlock(
    val reason: String,
    val newClasses: List<String>,
    val reappearedClasses: List<String>,
    val maxAbsence: String?,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ActiveTrackBlock(
    @JsonProperty("class") val className: String,
    val bbox: List<Int>,
    val firstSeen: String?,
    val lastSeen: String?,
    val matchedNow: Boolean,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class VerdictBlock(
    val time: String,
    val stage: String,
    val verdict: String,
    val reason: String,
    val classes: String,
    val summary: String?,
)

data class ErrorBlock(
    val error: String,
)

data class JudgeContextResult(
    val json: String,
    val errors: List<String>,
)
