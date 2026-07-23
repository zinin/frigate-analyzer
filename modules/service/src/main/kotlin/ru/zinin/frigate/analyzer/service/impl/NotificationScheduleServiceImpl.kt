package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.model.dto.NotificationSchedule
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import java.time.DateTimeException
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

@Service
class NotificationScheduleServiceImpl(
    private val settings: AppSettingsService,
) : NotificationScheduleService {
    override suspend fun getRecordingSchedule(): NotificationSchedule? =
        try {
            if (!isEnabled()) {
                null
            } else {
                val window = getWindow()
                val zone = getZone()
                if (window == null || zone == null) {
                    logger.warn {
                        "Notification schedule enabled but misconfigured (window=$window, zone=$zone); " +
                            "treating as disabled (fail-open)"
                    }
                    null
                } else {
                    NotificationSchedule(window, zone)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read notification schedule; treating as disabled (fail-open)" }
            null
        }

    override suspend fun isEnabled(): Boolean =
        settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED, default = false)

    override suspend fun getWindow(): ScheduleWindow? {
        val raw = settings.getString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_WINDOW) ?: return null
        val window = ScheduleWindow.parse(raw)
        if (window == null) {
            logger.warn { "Invalid stored schedule window '$raw'" }
        }
        return window
    }

    override suspend fun getZone(): ZoneId? {
        val raw = settings.getString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ZONE) ?: return null
        return try {
            ZoneId.of(raw)
        } catch (e: DateTimeException) {
            logger.warn { "Invalid stored schedule zone '$raw'" }
            null
        }
    }

    override suspend fun setEnabled(
        value: Boolean,
        updatedBy: String?,
    ) {
        settings.setBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED, value, updatedBy)
    }

    override suspend fun setWindow(
        window: ScheduleWindow,
        updatedBy: String?,
    ) {
        settings.setString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_WINDOW, window.storageFormat(), updatedBy)
    }

    override suspend fun setZone(
        zone: ZoneId,
        updatedBy: String?,
    ) {
        settings.setString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ZONE, zone.id, updatedBy)
    }
}
