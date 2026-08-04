package ru.zinin.frigate.analyzer.model.dto

data class NotificationDecision(
    val shouldNotify: Boolean,
    val reason: NotificationDecisionReason,
    val delta: DetectionDelta? = null,
)

enum class NotificationDecisionReason {
    NEW_OBJECTS,

    /**
     * No new track, but at least one match came back after an absence of at least
     * `ObjectTrackerProperties.reappearGap` — an object that had left the scene returned.
     */
    REAPPEARED,

    /**
     * A reappearance, suppressed because this camera announced one less than
     * `NotificationCooldownProperties.reappear` ago in *recording* time. Collapses the burst one
     * pass produces when it matches several long-untouched tracks in a row. `NEW_OBJECTS` is never
     * gated by it, and the tracker has already run — only the announcement is dropped.
     */
    COOLDOWN,
    ALL_REPEATED,
    NO_DETECTIONS,

    /**
     * Detections were present but the tracker returned an empty delta: they are handed to it raw
     * and unfiltered, and `confidenceFloor` dropped them all during clustering inside the tracker.
     */
    NO_VALID_DETECTIONS,
    GLOBAL_OFF,

    /** Recording's recordTimestamp falls outside the configured global notification window. */
    OUT_OF_SCHEDULE,
    TRACKER_ERROR,
}
