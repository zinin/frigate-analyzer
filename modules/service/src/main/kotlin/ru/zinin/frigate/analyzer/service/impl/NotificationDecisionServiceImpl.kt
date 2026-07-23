package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.model.dto.NotificationDecision
import ru.zinin.frigate.analyzer.model.dto.NotificationDecisionReason
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.NotificationDecisionService
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.service.ObjectTrackerService

private val logger = KotlinLogging.logger {}

@Service
class NotificationDecisionServiceImpl(
    private val tracker: ObjectTrackerService,
    private val settings: AppSettingsService,
    private val scheduleService: NotificationScheduleService,
) : NotificationDecisionService {
    override suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
        globalEnabled: Boolean?,
    ): NotificationDecision {
        if (detections.isEmpty()) {
            return NotificationDecision(false, NotificationDecisionReason.NO_DETECTIONS)
        }

        val resolvedGlobalEnabled = globalEnabled ?: isRecordingNotificationsGloballyEnabled()

        // Never throws: fail-open null on unreadable/corrupt settings (see NotificationScheduleService).
        // Deliberate asymmetry with the global flag: flag read failures propagate (recording stays
        // retryable), schedule read failures yield an EXTRA notification, never a lost one.
        val schedule = scheduleService.getRecordingSchedule()
        val scheduleAllows = schedule == null || schedule.contains(recording.recordTimestamp)

        return try {
            val delta = tracker.evaluate(recording, detections)
            when {
                delta.newTracksCount == 0 && delta.matchedTracksCount == 0 && delta.staleTracksCount == 0 -> {
                    logger.debug { "Decision: suppress (no_valid_detections): cam=${recording.camId} recording=${recording.id}" }
                    NotificationDecision(false, NotificationDecisionReason.NO_VALID_DETECTIONS, delta)
                }

                !resolvedGlobalEnabled -> {
                    logger.debug { "Decision: suppress (global_off): cam=${recording.camId} recording=${recording.id}" }
                    NotificationDecision(false, NotificationDecisionReason.GLOBAL_OFF, delta)
                }

                !scheduleAllows -> {
                    logger.debug { "Decision: suppress (out_of_schedule): cam=${recording.camId} recording=${recording.id}" }
                    NotificationDecision(false, NotificationDecisionReason.OUT_OF_SCHEDULE, delta)
                }

                delta.newTracksCount > 0 -> {
                    logger.debug {
                        "Decision: notify: cam=${recording.camId} newClasses=${delta.newClasses} recording=${recording.id}"
                    }
                    NotificationDecision(true, NotificationDecisionReason.NEW_OBJECTS, delta)
                }

                else -> {
                    logger.debug { "Decision: suppress (all_repeated): cam=${recording.camId} recording=${recording.id}" }
                    NotificationDecision(false, NotificationDecisionReason.ALL_REPEATED, delta)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val shouldNotify = resolvedGlobalEnabled && scheduleAllows
            logger.warn(e) {
                "Tracker failure for recording=${recording.id} cam=${recording.camId}; " +
                    "globalEnabled=$resolvedGlobalEnabled, scheduleAllows=$scheduleAllows, shouldNotify=$shouldNotify"
            }
            NotificationDecision(shouldNotify, NotificationDecisionReason.TRACKER_ERROR)
        }
    }

    override suspend fun isRecordingNotificationsGloballyEnabled(): Boolean =
        settings.getBoolean(
            AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED,
            default = true,
        )
}
