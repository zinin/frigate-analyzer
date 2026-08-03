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
import ru.zinin.frigate.analyzer.service.config.NotificationCooldownProperties
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class NotificationDecisionServiceImpl(
    private val tracker: ObjectTrackerService,
    private val settings: AppSettingsService,
    private val scheduleService: NotificationScheduleService,
    private val cooldown: NotificationCooldownProperties,
) : NotificationDecisionService {
    /**
     * Newest `recordTimestamp` a REAPPEARED notification has gone out for, per camera.
     *
     * In-memory on purpose: the horizon is minutes, the camera set is small, and losing the map on
     * restart costs at most one extra notification. Written only when this service decides to
     * notify — updating it on suppression too would turn the cooldown into a debounce that a
     * continuous stream of reappearances could hold shut forever.
     *
     * Never evicted. One entry per camera id ever seen, so a renamed or retired camera leaves a
     * stale entry behind; with a camera set in the single digits that is a few hundred bytes for the
     * process lifetime, and any eviction policy would cost more than it saves.
     *
     * "Decides to notify" is not "delivered": [ru.zinin.frigate.analyzer.core.facade.RecordingProcessingFacade]
     * sends after `evaluate` returns and swallows a send failure with a log line, by which point the
     * anchor is already advanced. A failed send therefore also mutes the camera's reappearances for
     * the length of the cooldown — the one fail-closed spot in a subsystem that is otherwise
     * uniformly fail-open. Accepted: sending is an enqueue onto the bot's own queue, so the window is
     * both rare and short. Confirming delivery back into the decision would invert the dependency
     * between the two layers, which is well outside what a cooldown is worth.
     */
    private val lastReappearNotified = ConcurrentHashMap<String, Instant>()

    override suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
        globalEnabled: Boolean?,
    ): NotificationDecision {
        if (detections.isEmpty()) {
            // Detection-less recordings must still advance the tracker's watch window: they are
            // the proof it was watching through a quiet period. Without this stamp the camera's
            // own silence reads as a processing interruption, and a real return after a long
            // absence — the flagship reappearance scenario — is suppressed as unobserved.
            tracker.markObserved(recording)
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

                // Both notify branches log at INFO, matching the signal-loss alerts: a notification
                // going out is what an operator watches for, and this is the only place `reason`
                // surfaces at all — the telegram layer never receives it. Suppressions stay on
                // DEBUG; they are the common case and would bury the signal.
                delta.newTracksCount > 0 -> {
                    logger.info {
                        "Decision: notify: cam=${recording.camId} newClasses=${delta.newClasses} recording=${recording.id}"
                    }
                    NotificationDecision(true, NotificationDecisionReason.NEW_OBJECTS, delta)
                }

                delta.reappearedTracksCount > 0 -> {
                    // Decides and arms in one atomic step; nothing to remember afterwards.
                    val sinceLast = reappearSuppressedBy(recording)
                    if (sinceLast != null) {
                        logger.debug {
                            "Decision: suppress (cooldown): cam=${recording.camId} " +
                                "sinceLast=$sinceLast recording=${recording.id}"
                        }
                        NotificationDecision(false, NotificationDecisionReason.COOLDOWN, delta)
                    } else {
                        logger.info {
                            "Decision: notify (reappeared): cam=${recording.camId} " +
                                "reappearedClasses=${delta.reappearedClasses} recording=${recording.id}"
                        }
                        NotificationDecision(true, NotificationDecisionReason.REAPPEARED, delta)
                    }
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

    /**
     * Distance from this camera's last announced reappearance to [recording] while the cooldown
     * still covers it; `null` when the notification may go out — in which case the window has
     * already been re-armed on [recording] by the time this returns.
     *
     * Signed for the log line, compared by absolute value. A burst is drained in whichever
     * direction the queue hands it over — newest-first is the normal case — and both directions
     * describe the same 24 seconds of one person walking past. Distance is also what keeps a
     * backlog intact: a recording hours from the anchor is a separate event on either side of it,
     * which is precisely what a wall-clock cooldown could not express.
     *
     * Deciding and arming in one `compute` rather than a read followed by a `merge` is what makes
     * it correct under concurrency. Several pipeline consumers evaluate recordings in parallel and
     * two of them can hold the same camera — [ObjectTrackerServiceImpl]'s `Watch` is built around
     * exactly that, and for exactly this reason keeps its own two halves inside a single `compute`.
     * A read-then-write pair here would let both callers see the same stale anchor and both notify,
     * and the burst that produces it — one pass matching a frame full of stale tracks — is the very
     * case this gate exists to collapse.
     *
     * Suppressing leaves the anchor untouched: the window is a cooldown, not a debounce, so a
     * continuous stream of reappearances cannot hold it shut. Arming takes the maximum rather than
     * the latest value seen, so a stuck recording re-picked with an hours-old timestamp announces
     * itself as its own event without dragging the window backwards over the live stream.
     */
    private fun reappearSuppressedBy(recording: RecordingDto): Duration? {
        if (!cooldown.reappearEnabled) return null
        val stamp = recording.recordTimestamp
        var suppressedBy: Duration? = null
        lastReappearNotified.compute(recording.camId) { _, last ->
            val sinceLast = last?.let { Duration.between(it, stamp) }
            if (sinceLast != null && sinceLast.abs() < cooldown.reappear) {
                suppressedBy = sinceLast
                last
            } else if (last != null) {
                maxOf(last, stamp)
            } else {
                stamp
            }
        }
        return suppressedBy
    }

    override suspend fun isRecordingNotificationsGloballyEnabled(): Boolean =
        settings.getBoolean(
            AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED,
            default = true,
        )
}
