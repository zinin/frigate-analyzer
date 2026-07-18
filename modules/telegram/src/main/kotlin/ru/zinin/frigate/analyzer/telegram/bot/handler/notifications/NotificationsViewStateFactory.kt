package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.dto.NotificationsViewState
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto

/**
 * Single assembly point for [NotificationsViewState]: the /notifications command handler,
 * the nfs-callback re-render in FrigateAnalyzerBot, and the schedule flow all build the
 * state here so global/schedule reads stay consistent.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class NotificationsViewStateFactory(
    private val appSettings: AppSettingsService,
    private val scheduleService: NotificationScheduleService,
) {
    suspend fun build(
        user: TelegramUserDto,
        isOwner: Boolean,
    ): NotificationsViewState =
        NotificationsViewState(
            isOwner = isOwner,
            recordingUserEnabled = user.notificationsRecordingEnabled,
            signalUserEnabled = user.notificationsSignalEnabled,
            recordingGlobalEnabled =
                if (isOwner) {
                    appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true)
                } else {
                    null
                },
            signalGlobalEnabled =
                if (isOwner) {
                    appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true)
                } else {
                    null
                },
            scheduleEnabled = if (isOwner) scheduleService.isEnabled() else null,
            scheduleWindow = if (isOwner) scheduleService.getWindow()?.displayFormat() else null,
            scheduleZone = if (isOwner) scheduleService.getZone()?.id else null,
            language = user.languageCode ?: "en",
        )
}
