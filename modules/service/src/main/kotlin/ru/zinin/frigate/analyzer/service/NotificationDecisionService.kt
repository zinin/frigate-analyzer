package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.dto.NotificationDecision
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity

interface NotificationDecisionService {
    /**
     * Decides whether to notify users about [recording] given its [detections].
     *
     * Order:
     *   1. detections empty → NO_DETECTIONS, no tracker call.
     *   2. use provided globalEnabled or read it from settings (may throw — propagates).
     *   3. tracker.evaluate → state is updated unconditionally so it stays coherent
     *      when the global toggle returns ON or the window opens.
     *   4. empty delta (the tracker clustered the detections and confidenceFloor filtered
     *      them all out) → NO_VALID_DETECTIONS.
     *   5. compose decision — reason = first tripped gate (normative precedence, see spec):
     *      GLOBAL_OFF if !globalEnabled, OUT_OF_SCHEDULE if the recording's recordTimestamp
     *      is outside the configured notification window (schedule read is fail-open and
     *      never throws), otherwise NEW_OBJECTS / ALL_REPEATED.
     *
     * On tracker exceptions: returns shouldNotify = globalEnabled && scheduleAllows with
     * TRACKER_ERROR — fail-open on the tracker itself, but the global toggle and the schedule
     * window both still gate. When either gate is closed no AI description supplier is invoked
     * and there is no fan-out.
     * Settings read exceptions propagate; they indicate the pipeline should stop/retry later.
     */
    suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
        globalEnabled: Boolean? = null,
    ): NotificationDecision

    suspend fun isRecordingNotificationsGloballyEnabled(): Boolean
}
