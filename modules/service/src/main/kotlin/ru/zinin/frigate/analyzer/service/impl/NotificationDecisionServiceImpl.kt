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
import ru.zinin.frigate.analyzer.service.ObjectTrackerService

private val logger = KotlinLogging.logger {}

@Service
class NotificationDecisionServiceImpl(
    private val tracker: ObjectTrackerService,
    private val settings: AppSettingsService,
) : NotificationDecisionService {
    override suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): NotificationDecision {
        if (detections.isEmpty()) {
            return NotificationDecision(false, NotificationDecisionReason.NO_DETECTIONS)
        }

        val globalEnabled = settings.getBoolean(
            AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, default = true,
        )

        return try {
            val delta = tracker.evaluate(recording, detections)
            when {
                delta.newTracksCount == 0 && delta.matchedTracksCount == 0 && delta.staleTracksCount == 0 -> {
                    logger.debug { "Decision: suppress (no_valid_detections): cam=${recording.camId} recording=${recording.id}" }
                    NotificationDecision(false, NotificationDecisionReason.NO_VALID_DETECTIONS, delta)
                }
                !globalEnabled -> {
                    logger.debug { "Decision: suppress (global_off): cam=${recording.camId} recording=${recording.id}" }
                    NotificationDecision(false, NotificationDecisionReason.GLOBAL_OFF, delta)
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
            logger.warn(e) {
                "Tracker failure for recording=${recording.id} cam=${recording.camId}; " +
                    "globalEnabled=$globalEnabled, shouldNotify=$globalEnabled"
            }
            NotificationDecision(globalEnabled, NotificationDecisionReason.TRACKER_ERROR)
        }
    }
}
