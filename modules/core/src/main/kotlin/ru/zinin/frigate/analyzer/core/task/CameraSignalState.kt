package ru.zinin.frigate.analyzer.core.task

import java.time.Instant

/**
 * In-memory state of a single camera in the signal-loss detector.
 *
 * The state machine has two variants:
 * - [Healthy]: the camera is currently producing recordings within the threshold.
 * - [SignalLost]: the gap between the last recording and `now` exceeds the threshold.
 *
 * Transitions are driven by the pure `decide(...)` function (see `SignalLossDecider.kt`).
 */
sealed class CameraSignalState {
    /** Most recent recording observed for this camera. */
    abstract val lastSeenAt: Instant

    /** Camera is producing recordings normally. */
    data class Healthy(
        override val lastSeenAt: Instant,
    ) : CameraSignalState()

    /**
     * Camera has stopped producing recordings.
     *
     * @property lastSeenAt the last recording observed BEFORE the loss — used to compute downtime on recovery.
     * @property notificationSent false during startup grace (alert is deferred).
     *           Flipped to true once the LOSS notification has been dispatched.
     *           This enables the late-alert flow: cameras dead before startup get alerted
     *           on the first tick AFTER grace ends.
     */
    data class SignalLost(
        override val lastSeenAt: Instant,
        val notificationSent: Boolean,
    ) : CameraSignalState()
}
