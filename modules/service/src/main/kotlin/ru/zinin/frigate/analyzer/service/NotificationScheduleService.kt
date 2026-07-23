package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.dto.NotificationSchedule
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import java.time.ZoneId

interface NotificationScheduleService {
    /**
     * Effective schedule for delivery decisions.
     * null — schedule disabled, not configured, or stored data is unusable (fail-open).
     * NEVER throws: transient/corrupt settings must not suppress notifications; the safe
     * failure direction here is an extra notification, never a lost one.
     */
    suspend fun getRecordingSchedule(): NotificationSchedule?

    /**
     * Raw enabled flag for the settings dialog (false when absent or invalid).
     * NOT fail-open: settings read failures propagate — only [getRecordingSchedule] never throws.
     */
    suspend fun isEnabled(): Boolean

    /**
     * Configured window regardless of the enabled flag; null when absent or corrupt.
     * NOT fail-open: settings read failures propagate — only [getRecordingSchedule] never throws.
     */
    suspend fun getWindow(): ScheduleWindow?

    /**
     * Configured zone regardless of the enabled flag; null when absent or corrupt.
     * NOT fail-open: settings read failures propagate — only [getRecordingSchedule] never throws.
     */
    suspend fun getZone(): ZoneId?

    suspend fun setEnabled(
        value: Boolean,
        updatedBy: String?,
    )

    suspend fun setWindow(
        window: ScheduleWindow,
        updatedBy: String?,
    )

    suspend fun setZone(
        zone: ZoneId,
        updatedBy: String?,
    )
}
