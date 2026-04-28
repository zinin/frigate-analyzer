package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class NotificationsSettingsCallbackHandler(
    private val userService: TelegramUserService,
    private val appSettings: AppSettingsService,
    private val renderer: NotificationsMessageRenderer,
) {
    enum class DispatchOutcome { RERENDER, CLOSE, UNAUTHORIZED, IGNORE }

    /**
     * Pure dispatch for testability: returns the outcome based on the callback data and the
     * caller's current state. Side-effects (DB updates) are issued here; the calling site
     * handles Telegram message editing.
     */
    suspend fun dispatch(
        data: String,
        chatId: Long,
        isOwner: Boolean,
        currentUser: TelegramUserDto,
    ): DispatchOutcome {
        val parts = data.split(":")
        return when {
            data == "nfs:close" -> DispatchOutcome.CLOSE
            parts.size == 4 && parts[0] == "nfs" -> {
                val targetEnabled = when (parts[3]) {
                    "1" -> true
                    "0" -> false
                    else -> {
                        logger.debug { "Ignoring nfs callback with malformed target state: $data" }
                        return DispatchOutcome.IGNORE
                    }
                }
                when (parts[1] to parts[2]) {
                    "u" to "rec" -> {
                        userService.updateNotificationsRecordingEnabled(chatId, targetEnabled)
                        DispatchOutcome.RERENDER
                    }
                    "u" to "sig" -> {
                        userService.updateNotificationsSignalEnabled(chatId, targetEnabled)
                        DispatchOutcome.RERENDER
                    }
                    "g" to "rec" -> {
                        if (!isOwner) return DispatchOutcome.UNAUTHORIZED
                        appSettings.setBoolean(
                            AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED,
                            targetEnabled,
                            currentUser.username,
                        )
                        DispatchOutcome.RERENDER
                    }
                    "g" to "sig" -> {
                        if (!isOwner) return DispatchOutcome.UNAUTHORIZED
                        appSettings.setBoolean(
                            AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED,
                            targetEnabled,
                            currentUser.username,
                        )
                        DispatchOutcome.RERENDER
                    }
                    else -> {
                        logger.debug { "Ignoring nfs callback with unknown scope/stream: $data" }
                        DispatchOutcome.IGNORE
                    }
                }
            }
            else -> {
                logger.debug { "Ignoring unknown nfs callback: $data" }
                DispatchOutcome.IGNORE
            }
        }
    }
}
