package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.telegram.dto.UserZoneInfo
import ru.zinin.frigate.analyzer.telegram.queue.NotificationTask
import ru.zinin.frigate.analyzer.telegram.queue.TelegramNotificationQueue
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

class TelegramNotificationServiceImplTest {
    private val userService = mockk<TelegramUserService>()
    private val notificationQueue = mockk<TelegramNotificationQueue>()
    private val uuidGeneratorHelper = mockk<UUIDGeneratorHelper>()
    private val service = TelegramNotificationServiceImpl(userService, notificationQueue, uuidGeneratorHelper)

    private val taskId = UUID.randomUUID()
    private val recordingId = UUID.randomUUID()
    private val chatId = 123L

    private fun createRecording(detectionsCount: Int = 1) =
        RecordingDto(
            id = recordingId,
            creationTimestamp = Instant.now(),
            filePath = "/recordings/camera1/2024-01-01/video.mp4",
            fileCreationTimestamp = Instant.now(),
            camId = "camera1",
            recordDate = LocalDate.of(2024, 1, 1),
            recordTime = LocalTime.of(12, 0),
            recordTimestamp = Instant.now(),
            startProcessingTimestamp = Instant.now(),
            processTimestamp = Instant.now(),
            processAttempts = 1,
            detectionsCount = detectionsCount,
            analyzeTime = 5,
            analyzedFramesCount = 10,
        )

    @Test
    fun `sendRecordingNotification propagates recordingId to NotificationTask`() =
        runTest {
            val recording = createRecording()
            val visualizedFrames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1),
                )
            val taskSlot = slot<NotificationTask>()

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(UserZoneInfo(chatId = chatId, zone = ZoneId.of("UTC")))
            coEvery { notificationQueue.enqueue(capture(taskSlot)) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames)

            coVerify(exactly = 1) { notificationQueue.enqueue(any()) }
            assertEquals(recordingId, taskSlot.captured.recordingId)
            assertEquals(taskId, taskSlot.captured.id)
            assertEquals(chatId, taskSlot.captured.chatId)
            assertEquals(visualizedFrames, taskSlot.captured.visualizedFrames)
        }

    @Test
    fun `sendRecordingNotification skips notification when no detections`() =
        runTest {
            val recording = createRecording(detectionsCount = 0)
            val visualizedFrames = emptyList<VisualizedFrameData>()

            service.sendRecordingNotification(recording, visualizedFrames)

            coVerify(exactly = 0) { notificationQueue.enqueue(any()) }
        }

    @Test
    fun `sendRecordingNotification skips notification when no subscribers`() =
        runTest {
            val recording = createRecording()
            val visualizedFrames = emptyList<VisualizedFrameData>()

            coEvery { userService.getAuthorizedUsersWithZones() } returns emptyList()

            service.sendRecordingNotification(recording, visualizedFrames)

            coVerify(exactly = 0) { notificationQueue.enqueue(any()) }
        }

    @Test
    fun `sendRecordingNotification sends to all subscribers with correct recordingId`() =
        runTest {
            val recording = createRecording()
            val visualizedFrames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1), detectionsCount = 1),
                )
            val tasks = mutableListOf<NotificationTask>()

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(
                    UserZoneInfo(chatId = 100L, zone = ZoneId.of("Europe/Moscow")),
                    UserZoneInfo(chatId = 200L, zone = ZoneId.of("Asia/Tokyo")),
                )
            coEvery { notificationQueue.enqueue(capture(tasks)) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames)

            assertEquals(2, tasks.size)
            tasks.forEach { task ->
                assertEquals(recordingId, task.recordingId)
            }
            assertEquals(100L, tasks[0].chatId)
            assertEquals(200L, tasks[1].chatId)
        }
}
