package ru.zinin.frigate.analyzer.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.NotificationSchedule
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationScheduleServiceImplTest {
    private val settings = mockk<AppSettingsService>()
    private val service = NotificationScheduleServiceImpl(settings)

    private fun stub(
        enabled: Boolean? = false,
        window: String? = null,
        zone: String? = null,
    ) {
        if (enabled != null) {
            coEvery {
                settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED, false)
            } returns enabled
        }
        coEvery { settings.getString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_WINDOW) } returns window
        coEvery { settings.getString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ZONE) } returns zone
    }

    @Test
    fun `disabled schedule yields null`() =
        runTest {
            stub(enabled = false, window = "00:00-07:00", zone = "Europe/Moscow")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `enabled with valid window and zone yields schedule`() =
        runTest {
            stub(enabled = true, window = "23:00-07:00", zone = "Europe/Moscow")
            assertEquals(
                NotificationSchedule(ScheduleWindow.parse("23:00-07:00")!!, ZoneId.of("Europe/Moscow")),
                service.getRecordingSchedule(),
            )
        }

    @Test
    fun `enabled without window yields null (fail-open)`() =
        runTest {
            stub(enabled = true, window = null, zone = "Europe/Moscow")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `enabled with corrupt window yields null (fail-open)`() =
        runTest {
            stub(enabled = true, window = "garbage", zone = "Europe/Moscow")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `enabled with equal start and end yields null (fail-open)`() =
        runTest {
            stub(enabled = true, window = "07:00-07:00", zone = "Europe/Moscow")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `enabled with corrupt zone yields null (fail-open)`() =
        runTest {
            stub(enabled = true, window = "00:00-07:00", zone = "Not/AZone")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `settings read failure yields null instead of throwing (fail-open)`() =
        runTest {
            coEvery {
                settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED, false)
            } throws RuntimeException("db down")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `getWindow parses raw value regardless of enabled flag`() =
        runTest {
            stub(enabled = null, window = "01:00-05:00")
            assertEquals(ScheduleWindow.parse("01:00-05:00"), service.getWindow())
        }

    @Test
    fun `getZone returns null for corrupt value`() =
        runTest {
            stub(enabled = null, zone = "wat")
            assertNull(service.getZone())
        }

    @Test
    fun `setters write the dedicated keys`() =
        runTest {
            coEvery { settings.setBoolean(any(), any(), any()) } returns Unit
            coEvery { settings.setString(any(), any(), any()) } returns Unit

            service.setEnabled(true, "owner")
            service.setWindow(ScheduleWindow.ofHours(0, 7), "owner")
            service.setZone(ZoneId.of("Europe/Moscow"), "owner")

            coVerify(exactly = 1) {
                settings.setBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED, true, "owner")
            }
            coVerify(exactly = 1) {
                settings.setString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_WINDOW, "00:00-07:00", "owner")
            }
            coVerify(exactly = 1) {
                settings.setString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ZONE, "Europe/Moscow", "owner")
            }
        }
}
