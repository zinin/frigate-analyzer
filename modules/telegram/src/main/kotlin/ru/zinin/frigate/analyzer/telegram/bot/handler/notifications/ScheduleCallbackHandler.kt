package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.DateTimeException
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

/**
 * Pure dispatch for the `nfs:g:sched:*` callback subtree (mirrors
 * [NotificationsSettingsCallbackHandler.dispatch]): mutations go through
 * [NotificationScheduleService]; Telegram I/O stays at the call site (ScheduleSettingsFlow).
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ScheduleCallbackHandler(
    private val scheduleService: NotificationScheduleService,
    private val userService: TelegramUserService,
) {
    sealed interface Outcome {
        data object RenderMain : Outcome

        data object RenderStartPicker : Outcome

        data class RenderEndPicker(
            val startHour: Int,
            val rejectedEqualEnd: Boolean = false,
        ) : Outcome

        data object RenderZoneScreen : Outcome

        data object AwaitManualZone : Outcome

        data object Unauthorized : Outcome

        data object Ignore : Outcome
    }

    suspend fun dispatch(
        data: String,
        isOwner: Boolean,
        ownerChatId: Long,
        updatedBy: String?,
    ): Outcome {
        if (!data.startsWith(PREFIX)) return Outcome.Ignore
        if (!isOwner) return Outcome.Unauthorized
        val action = data.removePrefix(PREFIX)
        val parts = action.split(":")
        return when {
            action == "on" -> {
                if (scheduleService.getWindow() == null) {
                    // Nothing to enable yet — lead the owner into configuration instead
                    // of creating an "on but empty" state that behaves as disabled.
                    Outcome.RenderStartPicker
                } else {
                    materializeZoneIfMissing(ownerChatId, updatedBy)
                    scheduleService.setEnabled(true, updatedBy)
                    Outcome.RenderMain
                }
            }

            action == "off" -> {
                scheduleService.setEnabled(false, updatedBy)
                Outcome.RenderMain
            }

            action == "cfg" -> {
                Outcome.RenderStartPicker
            }

            action == "zone" -> {
                Outcome.RenderZoneScreen
            }

            action == "zman" -> {
                Outcome.AwaitManualZone
            }

            action == "home" -> {
                Outcome.RenderMain
            }

            parts.size == 2 && parts[0] == "s" -> {
                val hour = parts[1].toIntOrNull()
                if (hour == null || hour !in 0..23) ignore(data) else Outcome.RenderEndPicker(hour)
            }

            parts.size == 3 && parts[0] == "e" -> {
                val start = parts[1].toIntOrNull()
                val end = parts[2].toIntOrNull()
                when {
                    // Range is checked BEFORE ofHours: an out-of-range hour makes LocalTime.of throw
                    // DateTimeException, which is NOT an IllegalArgumentException and would escape.
                    start == null || start !in 0..23 || end == null || end !in 0..23 -> {
                        ignore(data)
                    }

                    start == end -> {
                        Outcome.RenderEndPicker(start, rejectedEqualEnd = true)
                    }

                    else -> {
                        // Write-order invariant: window → zone → enabled LAST, so a concurrent
                        // reader sees either the old enabled=false or the complete new state.
                        scheduleService.setWindow(ScheduleWindow.ofHours(start, end), updatedBy)
                        materializeZoneIfMissing(ownerChatId, updatedBy)
                        scheduleService.setEnabled(true, updatedBy)
                        Outcome.RenderMain
                    }
                }
            }

            // Matched by structure, not by prefix: "zone"/"zman" are distinct actions that a
            // startsWith("z") check would silently swallow.
            parts.size == 2 && parts[0] == "z" -> {
                try {
                    scheduleService.setZone(ZoneId.of(parts[1]), updatedBy)
                    Outcome.RenderMain
                } catch (_: DateTimeException) {
                    ignore(data)
                }
            }

            else -> {
                ignore(data)
            }
        }
    }

    /**
     * Seeds the zone from the owner's timezone when it is still unset.
     *
     * Invariant: an enabled schedule always has a zone — "enabled + window + no zone"
     * (first enable, or an externally corrupted DB) must not survive any UI path, so
     * every branch that flips `enabled` to `true` calls this beforehand.
     */
    private suspend fun materializeZoneIfMissing(
        ownerChatId: Long,
        updatedBy: String?,
    ) {
        if (scheduleService.getZone() == null) {
            scheduleService.setZone(userService.getUserZone(ownerChatId), updatedBy)
        }
    }

    private fun ignore(data: String): Outcome {
        logger.debug { "Ignoring malformed sched callback: $data" }
        return Outcome.Ignore
    }

    companion object {
        const val PREFIX = "nfs:g:sched:"
    }
}
