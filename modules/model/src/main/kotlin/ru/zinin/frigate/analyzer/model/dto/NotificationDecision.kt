package ru.zinin.frigate.analyzer.model.dto

data class NotificationDecision(
    val shouldNotify: Boolean,
    val reason: NotificationDecisionReason,
    val delta: DetectionDelta? = null,
)

enum class NotificationDecisionReason {
    NEW_OBJECTS,
    ALL_REPEATED,
    NO_DETECTIONS,

    /** Detections were present but all were filtered out by `confidenceFloor` before tracker. */
    NO_VALID_DETECTIONS,
    GLOBAL_OFF,
    TRACKER_ERROR,
}
