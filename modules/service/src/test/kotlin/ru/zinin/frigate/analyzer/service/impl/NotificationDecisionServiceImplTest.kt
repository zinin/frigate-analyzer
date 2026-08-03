package ru.zinin.frigate.analyzer.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.DetectionDelta
import ru.zinin.frigate.analyzer.model.dto.NotificationDecisionReason
import ru.zinin.frigate.analyzer.model.dto.NotificationSchedule
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.service.ObjectTrackerService
import ru.zinin.frigate.analyzer.service.config.NotificationCooldownProperties
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationDecisionServiceImplTest {
    private val tracker = mockk<ObjectTrackerService>()
    private val settings = mockk<AppSettingsService>()
    private val scheduleService =
        mockk<NotificationScheduleService> {
            coEvery { getRecordingSchedule() } returns null
        }
    private val service =
        NotificationDecisionServiceImpl(tracker, settings, scheduleService, NotificationCooldownProperties())

    private val now = Instant.parse("2026-04-27T12:00:00Z")
    private val recording: RecordingDto =
        RecordingDto(
            id = UUID.randomUUID(),
            creationTimestamp = now,
            filePath = "/r.mp4",
            fileCreationTimestamp = now,
            camId = "cam",
            recordDate = LocalDate.from(now.atZone(ZoneOffset.UTC)),
            recordTime = LocalTime.from(now.atZone(ZoneOffset.UTC)),
            recordTimestamp = now,
            startProcessingTimestamp = now,
            processTimestamp = now,
            processAttempts = 1,
            detectionsCount = 1,
            analyzeTime = 1,
            analyzedFramesCount = 1,
            errorMessage = null,
        )

    /** A second recording knob: the cooldown is keyed by camera and measured on recordTimestamp. */
    private fun rec(
        camId: String = "cam",
        at: Instant = now,
    ): RecordingDto = recording.copy(id = UUID.randomUUID(), camId = camId, recordTimestamp = at)

    private fun serviceWith(cooldown: Duration) =
        NotificationDecisionServiceImpl(
            tracker,
            settings,
            scheduleService,
            NotificationCooldownProperties(reappear = cooldown),
        )

    private fun reappearance() = DetectionDelta(0, 1, 0, emptyList(), reappearedTracksCount = 1, reappearedClasses = listOf("person"))

    private fun det() =
        DetectionEntity(
            id = UUID.randomUUID(),
            creationTimestamp = now,
            recordingId = recording.id,
            detectionTimestamp = now,
            frameIndex = 0,
            model = "yolo",
            classId = 0,
            className = "car",
            confidence = 0.9f,
            x1 = 0f,
            y1 = 0f,
            x2 = 1f,
            y2 = 1f,
        )

    @Test
    fun `empty detections short-circuit to NO_DETECTIONS but still mark the camera observed`() =
        runTest {
            // markObserved keeps the tracker's watch window open through quiet periods; skipping it
            // here would make a camera's own silence look like a processing interruption and
            // suppress the next real reappearance as unobserved.
            justRun { tracker.markObserved(recording) }

            val decision = service.evaluate(recording, emptyList())

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.NO_DETECTIONS, decision.reason)
            verify(exactly = 1) { tracker.markObserved(recording) }
            coVerify(exactly = 0) { tracker.evaluate(any(), any()) }
        }

    @Test
    fun `global off keeps tracker running but suppresses notify`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns false
            coEvery { tracker.evaluate(recording, any()) } returns DetectionDelta(1, 0, 0, listOf("car"))

            val decision = service.evaluate(recording, listOf(det()))

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.GLOBAL_OFF, decision.reason)
            coVerify(exactly = 1) { tracker.evaluate(recording, any()) }
        }

    @Test
    fun `new tracks lead to NEW_OBJECTS and shouldNotify true`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns DetectionDelta(1, 0, 0, listOf("car"))

            val decision = service.evaluate(recording, listOf(det()))

            assertTrue(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.NEW_OBJECTS, decision.reason)
        }

    @Test
    fun `all matched leads to ALL_REPEATED and shouldNotify false`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns DetectionDelta(0, 1, 0, emptyList())

            val decision = service.evaluate(recording, listOf(det()))

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.ALL_REPEATED, decision.reason)
        }

    @Test
    fun `reappeared track leads to REAPPEARED and shouldNotify true`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(0, 1, 0, emptyList(), reappearedTracksCount = 1, reappearedClasses = listOf("person"))

            val decision = service.evaluate(recording, listOf(det()))

            assertTrue(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.REAPPEARED, decision.reason)
        }

    @Test
    fun `NEW_OBJECTS wins over REAPPEARED when both are present`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(1, 1, 0, listOf("car"), reappearedTracksCount = 1, reappearedClasses = listOf("person"))

            val decision = service.evaluate(recording, listOf(det()))

            assertTrue(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.NEW_OBJECTS, decision.reason)
        }

    @Test
    fun `schedule still gates a reappeared track`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { scheduleService.getRecordingSchedule() } returns
                NotificationSchedule(ScheduleWindow.ofHours(1, 2), ZoneId.of("UTC"))
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(0, 1, 0, emptyList(), reappearedTracksCount = 1, reappearedClasses = listOf("person"))

            val decision = service.evaluate(recording, listOf(det()))

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.OUT_OF_SCHEDULE, decision.reason)
        }

    @Test
    fun `global off still gates a reappeared track`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns false
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(0, 1, 0, emptyList(), reappearedTracksCount = 1, reappearedClasses = listOf("person"))

            val decision = service.evaluate(recording, listOf(det()))

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.GLOBAL_OFF, decision.reason)
        }

    @Test
    fun `tracker returns empty delta for confidence-filtered detections leads to NO_VALID_DETECTIONS`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns DetectionDelta(0, 0, 0, emptyList())

            val decision = service.evaluate(recording, listOf(det()))

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.NO_VALID_DETECTIONS, decision.reason)
        }

    @Test
    fun `tracker exception with global ON leads to TRACKER_ERROR and shouldNotify true (fail-open)`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } throws RuntimeException("db down")

            val decision = service.evaluate(recording, listOf(det()))

            assertTrue(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.TRACKER_ERROR, decision.reason)
        }

    @Test
    fun `tracker exception with global OFF leads to TRACKER_ERROR and shouldNotify false (global wins)`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns false
            coEvery { tracker.evaluate(recording, any()) } throws RuntimeException("db down")

            val decision = service.evaluate(recording, listOf(det()))

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.TRACKER_ERROR, decision.reason)
        }

    @Test
    fun `settings read exception propagates and tracker is not called`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } throws
                RuntimeException("settings db down")

            assertFailsWith<RuntimeException> {
                service.evaluate(recording, listOf(det()))
            }
            coVerify(exactly = 0) { tracker.evaluate(any(), any()) }
        }

    @Test
    fun `provided global setting bypasses settings read`() =
        runTest {
            coEvery { tracker.evaluate(recording, any()) } returns DetectionDelta(1, 0, 0, listOf("car"))

            val decision = service.evaluate(recording, listOf(det()), globalEnabled = false)

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.GLOBAL_OFF, decision.reason)
            coVerify(exactly = 0) {
                settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true)
            }
        }

    private val nightUtc = NotificationSchedule(ScheduleWindow.parse("00:00-07:00")!!, ZoneId.of("UTC"))
    private val dayUtc = NotificationSchedule(ScheduleWindow.parse("10:00-14:00")!!, ZoneId.of("UTC"))

    @Test
    fun `recording outside schedule window is suppressed with OUT_OF_SCHEDULE`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns nightUtc
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(1, 0, 0, listOf("car"))

            val decision = service.evaluate(recording, listOf(det()))

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.OUT_OF_SCHEDULE, decision.reason)
            coVerify(exactly = 1) { tracker.evaluate(recording, any()) }
        }

    @Test
    fun `recording inside schedule window notifies as usual`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns dayUtc
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(1, 0, 0, listOf("car"))

            val decision = service.evaluate(recording, listOf(det()))

            assertTrue(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.NEW_OBJECTS, decision.reason)
        }

    @Test
    fun `GLOBAL_OFF wins over OUT_OF_SCHEDULE`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns nightUtc
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(1, 0, 0, listOf("car"))

            val decision = service.evaluate(recording, listOf(det()), globalEnabled = false)

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.GLOBAL_OFF, decision.reason)
        }

    @Test
    fun `OUT_OF_SCHEDULE wins over ALL_REPEATED (first tripped gate)`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns nightUtc
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(0, 1, 0, emptyList())

            val decision = service.evaluate(recording, listOf(det()))

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.OUT_OF_SCHEDULE, decision.reason)
        }

    @Test
    fun `NO_VALID_DETECTIONS wins over OUT_OF_SCHEDULE (empty delta is checked first)`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns nightUtc
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(0, 0, 0, emptyList())

            val decision = service.evaluate(recording, listOf(det()))

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.NO_VALID_DETECTIONS, decision.reason)
        }

    @Test
    fun `tracker error honors schedule (outside window means no notify)`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns nightUtc
            coEvery { tracker.evaluate(recording, any()) } throws RuntimeException("boom")

            val decision = service.evaluate(recording, listOf(det()), globalEnabled = true)

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.TRACKER_ERROR, decision.reason)
        }

    @Test
    fun `tracker error inside window stays fail-open`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns dayUtc
            coEvery { tracker.evaluate(recording, any()) } throws RuntimeException("boom")

            val decision = service.evaluate(recording, listOf(det()), globalEnabled = true)

            assertTrue(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.TRACKER_ERROR, decision.reason)
        }

    @Test
    fun `with the cooldown at its default every reappearance still notifies`() =
        runTest {
            // Acceptance criterion: unconfigured behaviour must be byte-identical to v0.9.1.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()

            val first = service.evaluate(rec(at = now), listOf(det()))
            val second = service.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertEquals(NotificationDecisionReason.REAPPEARED, first.reason)
            assertEquals(NotificationDecisionReason.REAPPEARED, second.reason)
            assertTrue(second.shouldNotify)
        }

    @Test
    fun `the cooldown collapses a burst of reappearances on one camera`() =
        runTest {
            // Group B of the production run: one person walking past ~107 stale person tracks,
            // matching them one after another, four notifications in 24 seconds.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            val first = svc.evaluate(rec(at = now), listOf(det()))
            val burst =
                listOf(2L, 13L, 24L).map { svc.evaluate(rec(at = now.plusSeconds(it)), listOf(det())) }

            assertTrue(first.shouldNotify)
            assertTrue(burst.all { !it.shouldNotify })
            assertTrue(burst.all { it.reason == NotificationDecisionReason.COOLDOWN })
        }

    @Test
    fun `a suppressed reappearance keeps the delta so the log line stays diagnosable`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            svc.evaluate(rec(at = now), listOf(det()))
            val suppressed = svc.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertEquals(listOf("person"), suppressed.delta?.reappearedClasses)
        }

    @Test
    fun `the cooldown expires and the next reappearance notifies again`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            svc.evaluate(rec(at = now), listOf(det()))
            val later = svc.evaluate(rec(at = now.plus(Duration.ofMinutes(6))), listOf(det()))

            assertTrue(later.shouldNotify)
            assertEquals(NotificationDecisionReason.REAPPEARED, later.reason)
        }

    @Test
    fun `the cooldown window is measured from the last notification, not slid by suppressed ones`() =
        runTest {
            // A cooldown, not a debounce: a continuous stream of reappearances must not hold the
            // gate shut forever.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            svc.evaluate(rec(at = now), listOf(det()))
            svc.evaluate(rec(at = now.plus(Duration.ofMinutes(4))), listOf(det()))
            val later = svc.evaluate(rec(at = now.plus(Duration.ofMinutes(6))), listOf(det()))

            assertTrue(later.shouldNotify)
        }

    @Test
    fun `the cooldown is per camera`() =
        runTest {
            // Group B spanned cam2 and cam3 within two seconds; both must still be announced.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            val cam3 = svc.evaluate(rec(camId = "cam3", at = now), listOf(det()))
            val cam2 = svc.evaluate(rec(camId = "cam2", at = now.plusSeconds(2)), listOf(det()))

            assertTrue(cam3.shouldNotify)
            assertTrue(cam2.shouldNotify)
        }

    @Test
    fun `the cooldown never gates NEW_OBJECTS`() =
        runTest {
            // A genuinely new object must not be lost because something reappeared a moment ago.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            val svc = serviceWith(Duration.ofMinutes(5))
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            svc.evaluate(rec(at = now), listOf(det()))

            coEvery { tracker.evaluate(any(), any()) } returns DetectionDelta(1, 0, 0, listOf("car"))
            val fresh = svc.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertTrue(fresh.shouldNotify)
            assertEquals(NotificationDecisionReason.NEW_OBJECTS, fresh.reason)
        }

    @Test
    fun `a NEW_OBJECTS notification does not open or close the reappear cooldown`() =
        runTest {
            // The gate order puts NEW_OBJECTS first, so that branch never touches the anchor.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            val svc = serviceWith(Duration.ofMinutes(5))
            coEvery { tracker.evaluate(any(), any()) } returns DetectionDelta(1, 0, 0, listOf("car"))
            svc.evaluate(rec(at = now), listOf(det()))

            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val reappeared = svc.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertTrue(reappeared.shouldNotify)
            assertEquals(NotificationDecisionReason.REAPPEARED, reappeared.reason)
        }

    @Test
    fun `a backlog drained in seconds is not collapsed, because the clock never enters the decision`() =
        runTest {
            // The reason the cooldown is measured on recordTimestamp. After a restart the queue is
            // drained newest-first with no floor on age: an hour of recordings is evaluated within
            // seconds, and a wall-clock cooldown would announce one of them and swallow the rest.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            val newest = svc.evaluate(rec(at = now), listOf(det()))
            val anHourBack = svc.evaluate(rec(at = now.minus(Duration.ofHours(1))), listOf(det()))
            val twoHoursBack = svc.evaluate(rec(at = now.minus(Duration.ofHours(2))), listOf(det()))

            assertTrue(newest.shouldNotify)
            assertTrue(anHourBack.shouldNotify)
            assertTrue(twoHoursBack.shouldNotify)
        }

    @Test
    fun `a burst drained newest-first collapses just as one drained oldest-first does`() =
        runTest {
            // Same 24-second burst, arriving in the other direction — which is the direction the
            // newest-first drain actually produces. Distance is what matters, not its sign.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            val newest = svc.evaluate(rec(at = now.plusSeconds(24)), listOf(det()))
            val middle = svc.evaluate(rec(at = now.plusSeconds(13)), listOf(det()))
            val oldest = svc.evaluate(rec(at = now), listOf(det()))

            assertTrue(newest.shouldNotify)
            assertEquals(NotificationDecisionReason.COOLDOWN, middle.reason)
            assertEquals(NotificationDecisionReason.COOLDOWN, oldest.reason)
        }

    @Test
    fun `an out-of-order old recording cannot reopen the window for the live stream`() =
        runTest {
            // A stuck recording re-picked after its cooldown arrives with an hours-old timestamp.
            // It is far enough away to be its own event, but the anchor must stay at the newest
            // notified recording — otherwise the live stream's next match would notify again.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            svc.evaluate(rec(at = now), listOf(det()))
            val stuck = svc.evaluate(rec(at = now.minus(Duration.ofHours(6))), listOf(det()))
            val live = svc.evaluate(rec(at = now.plusSeconds(30)), listOf(det()))

            assertTrue(stuck.shouldNotify)
            assertEquals(NotificationDecisionReason.COOLDOWN, live.reason)
        }

    @Test
    fun `a suppressed reappearance still ran the tracker, so the watch window kept advancing`() =
        runTest {
            // The tracker is called before every gate on purpose. Were the cooldown to skip it,
            // the window would stop being stamped and the suppressed notification would come back
            // later as a false reappearance.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))
            val second = rec(at = now.plusSeconds(2))

            svc.evaluate(rec(at = now), listOf(det()))
            val suppressed = svc.evaluate(second, listOf(det()))

            assertEquals(NotificationDecisionReason.COOLDOWN, suppressed.reason)
            coVerify(exactly = 1) { tracker.evaluate(second, any()) }
        }

    @Test
    fun `a detection-less recording leaves the cooldown untouched`() =
        runTest {
            // NO_DETECTIONS short-circuits above every gate, so the anchor is neither read nor
            // written. Cheap insurance: were the cooldown ever hoisted above that branch, a camera's
            // own quiet stretch would start eating its next reappearance.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))
            svc.evaluate(rec(at = now), listOf(det()))

            val quiet = rec(at = now.plusSeconds(2))
            justRun { tracker.markObserved(quiet) }
            val silent = svc.evaluate(quiet, emptyList())
            val within = svc.evaluate(rec(at = now.plusSeconds(4)), listOf(det()))

            assertEquals(NotificationDecisionReason.NO_DETECTIONS, silent.reason)
            // The anchor still sits where the first notification put it — untouched, not refreshed.
            assertEquals(NotificationDecisionReason.COOLDOWN, within.reason)
        }

    @Test
    fun `a reappearance suppressed by the global toggle does not arm the cooldown`() =
        runTest {
            // GLOBAL_OFF sits above REAPPEARED, so the branch that writes the anchor is never
            // reached. Once the toggle is back on, the next reappearance must go out at once —
            // otherwise switching notifications off would silently swallow the first one after.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns false
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            val off = svc.evaluate(rec(at = now), listOf(det()))
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            val on = svc.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertEquals(NotificationDecisionReason.GLOBAL_OFF, off.reason)
            assertTrue(on.shouldNotify)
            assertEquals(NotificationDecisionReason.REAPPEARED, on.reason)
        }

    @Test
    fun `a reappearance suppressed by the schedule does not arm the cooldown`() =
        runTest {
            // Same argument one gate down. Reuses the existing nightUtc / dayUtc fixtures.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            coEvery { scheduleService.getRecordingSchedule() } returns nightUtc
            val closed = svc.evaluate(rec(at = now), listOf(det()))
            coEvery { scheduleService.getRecordingSchedule() } returns dayUtc
            val open = svc.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertEquals(NotificationDecisionReason.OUT_OF_SCHEDULE, closed.reason)
            assertTrue(open.shouldNotify)
        }

    @Test
    fun `a negative cooldown is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationCooldownProperties(reappear = Duration.ofSeconds(-1))
        }
    }
}
