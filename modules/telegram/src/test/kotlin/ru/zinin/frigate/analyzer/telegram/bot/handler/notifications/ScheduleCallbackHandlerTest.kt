package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.ZoneId
import kotlin.test.assertEquals

class ScheduleCallbackHandlerTest {
    private val scheduleService = mockk<NotificationScheduleService>(relaxed = true)
    private val userService = mockk<TelegramUserService>(relaxed = true)
    private val handler = ScheduleCallbackHandler(scheduleService, userService)

    private val chatId = 100L

    private suspend fun dispatch(
        data: String,
        isOwner: Boolean = true,
    ) = handler.dispatch(data, isOwner, chatId, "owner")

    @Test
    fun `non-owner is rejected without side effects`() =
        runTest {
            val outcome = dispatch("nfs:g:sched:on", isOwner = false)
            assertEquals(ScheduleCallbackHandler.Outcome.Unauthorized, outcome)
            coVerify(exactly = 0) { scheduleService.setEnabled(any(), any()) }
        }

    @Test
    fun `payload outside the schedule subtree is ignored, not rejected`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:close", isOwner = false))
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:rec:0"))
        }

    @Test
    fun `on with configured window enables and re-renders main`() =
        runTest {
            coEvery { scheduleService.getWindow() } returns ScheduleWindow.ofHours(0, 7)
            val outcome = dispatch("nfs:g:sched:on")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 1) { scheduleService.setEnabled(true, "owner") }
        }

    @Test
    fun `on with window but missing zone materializes zone before enabling`() =
        runTest {
            coEvery { scheduleService.getWindow() } returns ScheduleWindow.ofHours(0, 7)
            coEvery { scheduleService.getZone() } returns null
            coEvery { userService.getUserZone(chatId) } returns ZoneId.of("Europe/Moscow")

            val outcome = dispatch("nfs:g:sched:on")

            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 1) { scheduleService.setZone(ZoneId.of("Europe/Moscow"), "owner") }
            coVerify(exactly = 1) { scheduleService.setEnabled(true, "owner") }
            coVerifyOrder {
                scheduleService.setZone(ZoneId.of("Europe/Moscow"), "owner")
                scheduleService.setEnabled(true, "owner")
            }
        }

    @Test
    fun `on without window opens the start picker instead of enabling`() =
        runTest {
            coEvery { scheduleService.getWindow() } returns null
            val outcome = dispatch("nfs:g:sched:on")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderStartPicker, outcome)
            coVerify(exactly = 0) { scheduleService.setEnabled(any(), any()) }
        }

    @Test
    fun `off disables and re-renders main`() =
        runTest {
            val outcome = dispatch("nfs:g:sched:off")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 1) { scheduleService.setEnabled(false, "owner") }
        }

    @Test
    fun `cfg opens start picker`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.RenderStartPicker, dispatch("nfs:g:sched:cfg"))
        }

    @Test
    fun `start hour selection opens end picker with the hour`() =
        runTest {
            assertEquals(
                ScheduleCallbackHandler.Outcome.RenderEndPicker(23, rejectedEqualEnd = false),
                dispatch("nfs:g:sched:s:23"),
            )
        }

    @Test
    fun `single-digit start hour is parsed as is`() =
        runTest {
            assertEquals(
                ScheduleCallbackHandler.Outcome.RenderEndPicker(0, rejectedEqualEnd = false),
                dispatch("nfs:g:sched:s:0"),
            )
        }

    @Test
    fun `start hour out of range is ignored`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:s:24"))
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:s:x"))
        }

    @Test
    fun `end equal to start re-renders end picker with warning and writes nothing`() =
        runTest {
            val outcome = dispatch("nfs:g:sched:e:5:5")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderEndPicker(5, rejectedEqualEnd = true), outcome)
            coVerify(exactly = 0) { scheduleService.setWindow(any(), any()) }
            coVerify(exactly = 0) { scheduleService.setEnabled(any(), any()) }
        }

    @Test
    fun `end selection saves window, materializes zone from owner, auto-enables`() =
        runTest {
            coEvery { scheduleService.getZone() } returns null
            coEvery { userService.getUserZone(chatId) } returns ZoneId.of("Europe/Moscow")

            val outcome = dispatch("nfs:g:sched:e:23:7")

            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 1) { scheduleService.setWindow(ScheduleWindow.ofHours(23, 7), "owner") }
            coVerify(exactly = 1) { scheduleService.setZone(ZoneId.of("Europe/Moscow"), "owner") }
            coVerify(exactly = 1) { scheduleService.setEnabled(true, "owner") }
            // Write-order invariant: enabled goes LAST, so a concurrent reader sees either the
            // old enabled=false or the complete new state — never "enabled with no window".
            coVerifyOrder {
                scheduleService.setWindow(ScheduleWindow.ofHours(23, 7), "owner")
                scheduleService.setZone(ZoneId.of("Europe/Moscow"), "owner")
                scheduleService.setEnabled(true, "owner")
            }
        }

    @Test
    fun `end selection keeps existing zone`() =
        runTest {
            coEvery { scheduleService.getZone() } returns ZoneId.of("Asia/Omsk")

            dispatch("nfs:g:sched:e:0:7")

            coVerify(exactly = 0) { scheduleService.setZone(any(), any()) }
            coVerify(exactly = 1) { scheduleService.setWindow(ScheduleWindow.ofHours(0, 7), "owner") }
        }

    @Test
    fun `end hour out of range is ignored`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:e:0:24"))
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:e:x:7"))
        }

    @Test
    fun `start hour out of range inside an end payload is ignored`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:e:24:7"))
            coVerify(exactly = 0) { scheduleService.setWindow(any(), any()) }
        }

    @Test
    fun `zone opens zone screen`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.RenderZoneScreen, dispatch("nfs:g:sched:zone"))
        }

    @Test
    fun `zone preset saves and re-renders main`() =
        runTest {
            val outcome = dispatch("nfs:g:sched:z:Asia/Irkutsk")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 1) { scheduleService.setZone(ZoneId.of("Asia/Irkutsk"), "owner") }
        }

    @Test
    fun `invalid zone preset is ignored`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:z:Not/AZone"))
            coVerify(exactly = 0) { scheduleService.setZone(any(), any()) }
        }

    @Test
    fun `zman awaits manual input`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.AwaitManualZone, dispatch("nfs:g:sched:zman"))
        }

    @Test
    fun `home re-renders main without changes`() =
        runTest {
            val outcome = dispatch("nfs:g:sched:home")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 0) { scheduleService.setEnabled(any(), any()) }
            coVerify(exactly = 0) { scheduleService.setWindow(any(), any()) }
            coVerify(exactly = 0) { scheduleService.setZone(any(), any()) }
        }

    @Test
    fun `unknown action is ignored`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:wat"))
        }
}
