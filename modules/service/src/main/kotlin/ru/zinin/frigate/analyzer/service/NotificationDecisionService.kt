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
     *   2. use provided globalEnabled or read it from settings. The fallback read MAY THROW and
     *      the exception propagates — a settings failure here means the pipeline should stop and
     *      retry the recording later.
     *   3. read the schedule. Deliberately the opposite of (2): fail-open, NEVER throws, degrades
     *      to "no schedule" on unreadable or corrupt settings, so a failure costs an extra
     *      notification rather than a lost one. Both reads must stay OUTSIDE the try below —
     *      the tracker-failure path consumes their results.
     *   4. tracker.evaluate → state is updated unconditionally so it stays coherent
     *      when the global toggle returns ON or the window opens.
     *   5. empty delta (the tracker clustered the detections and confidenceFloor filtered
     *      them all out) → NO_VALID_DETECTIONS.
     *   6. compose decision — reason = first tripped gate (normative precedence, see spec):
     *      GLOBAL_OFF if !globalEnabled, OUT_OF_SCHEDULE if the recording's recordTimestamp
     *      is outside the configured notification window, otherwise NEW_OBJECTS / ALL_REPEATED.
     *
     * On tracker exceptions: returns shouldNotify = globalEnabled && scheduleAllows with
     * TRACKER_ERROR — fail-open on the tracker itself, but the global toggle and the schedule
     * window both still gate. When either gate is closed no AI description supplier is invoked
     * and there is no fan-out.
     */
    suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
        globalEnabled: Boolean? = null,
    ): NotificationDecision

    suspend fun isRecordingNotificationsGloballyEnabled(): Boolean
}
