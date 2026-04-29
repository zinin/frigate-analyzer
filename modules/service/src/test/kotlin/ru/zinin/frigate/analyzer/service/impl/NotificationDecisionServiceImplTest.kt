package ru.zinin.frigate.analyzer.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.DetectionDelta
import ru.zinin.frigate.analyzer.model.dto.NotificationDecisionReason
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.ObjectTrackerService
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationDecisionServiceImplTest {
    private val tracker = mockk<ObjectTrackerService>()
    private val settings = mockk<AppSettingsService>()
    private val service = NotificationDecisionServiceImpl(tracker, settings)

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
    fun `empty detections short-circuit to NO_DETECTIONS without calling tracker`() =
        runTest {
            val decision = service.evaluate(recording, emptyList())

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.NO_DETECTIONS, decision.reason)
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
}
