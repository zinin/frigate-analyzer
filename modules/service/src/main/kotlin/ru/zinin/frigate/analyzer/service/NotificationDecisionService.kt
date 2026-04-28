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
     *   2. read globalEnabled (may throw — propagates).
     *   3. cluster detections → if all filtered by confidenceFloor, NO_VALID_DETECTIONS, no tracker call.
     *   4. tracker.evaluate → state is updated unconditionally so it stays coherent
     *      when the global toggle returns ON.
     *   5. compose decision: GLOBAL_OFF if !globalEnabled, otherwise NEW_OBJECTS / ALL_REPEATED.
     *
     * On tracker exceptions while globalEnabled = true: returns shouldNotify = true with
     * TRACKER_ERROR (fail-open).
     * On tracker exceptions while globalEnabled = false: returns shouldNotify = false with
     * TRACKER_ERROR — global OFF wins, no AI description supplier is invoked, no fan-out.
     * Settings read exceptions propagate; they indicate the pipeline should stop/retry later.
     */
    suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): NotificationDecision
}
