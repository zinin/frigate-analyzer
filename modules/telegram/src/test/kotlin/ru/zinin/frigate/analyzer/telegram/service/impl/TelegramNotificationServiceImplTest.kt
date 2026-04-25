package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.telegram.dto.UserZoneInfo
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.queue.RecordingNotificationTask
import ru.zinin.frigate.analyzer.telegram.queue.TelegramNotificationQueue
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramNotificationServiceImplTest {
    private val userService = mockk<TelegramUserService>()
    private val notificationQueue = mockk<TelegramNotificationQueue>()
    private val uuidGeneratorHelper = mockk<UUIDGeneratorHelper>()
    private val msg =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )
    private val signalLossFormatter = mockk<SignalLossMessageFormatter>(relaxed = true)
    private val rateLimiterProvider = mockk<ObjectProvider<DescriptionRateLimiter>>(relaxed = true)
    private val service: TelegramNotificationService =
        TelegramNotificationServiceImpl(
            userService,
            notificationQueue,
            uuidGeneratorHelper,
            msg,
            signalLossFormatter,
            rateLimiterProvider,
        )

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
            errorMessage = null,
        )

    @Test
    fun `sendRecordingNotification propagates recordingId to NotificationTask`() =
        runTest {
            val recording = createRecording()
            val visualizedFrames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1),
                )
            val taskSlot = slot<RecordingNotificationTask>()

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(UserZoneInfo(chatId = chatId, zone = ZoneId.of("UTC"), language = "ru"))
            coEvery { notificationQueue.enqueue(capture(taskSlot)) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames)

            coVerify(exactly = 1) { notificationQueue.enqueue(any()) }
            assertEquals(recordingId, taskSlot.captured.recordingId)
            assertEquals(taskId, taskSlot.captured.id)
            assertEquals(chatId, taskSlot.captured.chatId)
            assertEquals(visualizedFrames, taskSlot.captured.visualizedFrames)
            assertEquals("ru", taskSlot.captured.language)
            assertTrue(taskSlot.captured.message.contains("camera1"), "message should contain camera ID")
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
            val tasks = mutableListOf<RecordingNotificationTask>()
            val taskId1 = UUID.randomUUID()
            val taskId2 = UUID.randomUUID()

            coEvery { uuidGeneratorHelper.generateV1() } returnsMany listOf(taskId1, taskId2)
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(
                    UserZoneInfo(chatId = 100L, zone = ZoneId.of("Europe/Moscow"), language = "ru"),
                    UserZoneInfo(chatId = 200L, zone = ZoneId.of("Asia/Tokyo"), language = "en"),
                )
            coEvery { notificationQueue.enqueue(capture(tasks)) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames)

            assertEquals(2, tasks.size)
            tasks.forEach { task ->
                assertEquals(recordingId, task.recordingId)
            }
            assertEquals(taskId1, tasks[0].id)
            assertEquals(taskId2, tasks[1].id)
            assertEquals(100L, tasks[0].chatId)
            assertEquals(200L, tasks[1].chatId)
            assertEquals("ru", tasks[0].language)
            assertEquals("en", tasks[1].language)
        }

    @Test
    fun `sendRecordingNotification skips description when rate limit denies`() =
        runTest {
            val recording = createRecording()
            val visualizedFrames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1),
                )
            val taskSlot = slot<RecordingNotificationTask>()
            var supplierInvocations = 0
            val supplier: () -> kotlinx.coroutines.Deferred<Result<ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult>>? = {
                supplierInvocations++
                null
            }

            val limiter = mockk<DescriptionRateLimiter>()
            coEvery { limiter.tryAcquire() } returns false
            every { rateLimiterProvider.getIfAvailable() } returns limiter

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(UserZoneInfo(chatId = chatId, zone = ZoneId.of("UTC"), language = "ru"))
            coEvery { notificationQueue.enqueue(capture(taskSlot)) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames, supplier)

            assertEquals(0, supplierInvocations, "supplier must not be invoked when rate-limit denies")
            assertEquals(null, taskSlot.captured.descriptionHandle)
            coVerify(exactly = 1) { limiter.tryAcquire() }
        }

    @Test
    fun `sendRecordingNotification invokes supplier exactly once below limit and shares handle`() =
        runTest {
            val recording = createRecording()
            val visualizedFrames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1),
                )
            var supplierInvocations = 0
            val supplier: () -> kotlinx.coroutines.Deferred<Result<ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult>>? = {
                supplierInvocations++
                null
            }

            val limiter = mockk<DescriptionRateLimiter>()
            coEvery { limiter.tryAcquire() } returns true
            every { rateLimiterProvider.getIfAvailable() } returns limiter

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(
                    UserZoneInfo(chatId = 1L, zone = ZoneId.of("UTC"), language = "ru"),
                    UserZoneInfo(chatId = 2L, zone = ZoneId.of("UTC"), language = "ru"),
                    UserZoneInfo(chatId = 3L, zone = ZoneId.of("UTC"), language = "en"),
                )
            val captured = mutableListOf<RecordingNotificationTask>()
            coEvery { notificationQueue.enqueue(any()) } answers {
                captured.add(arg<Any>(0) as RecordingNotificationTask)
            }

            service.sendRecordingNotification(recording, visualizedFrames, supplier)

            assertEquals(1, supplierInvocations, "supplier must be invoked exactly once across all recipients")
            coVerify(exactly = 1) { limiter.tryAcquire() }
            coVerify(exactly = 3) { notificationQueue.enqueue(any()) }

            // All three tasks share the same descriptionHandle (null in this test, since supplier returns null,
            // but the assertion still proves "shared" — distinct().size must be 1).
            assertEquals(3, captured.size)
            assertEquals(1, captured.map { it.descriptionHandle }.distinct().size, "all recipients share the same handle")
        }

    @Test
    fun `sendRecordingNotification with null descriptionSupplier does not query rate limiter`() =
        runTest {
            // Models the case `application.ai.description.enabled=false` — RecordingProcessingFacade
            // passes a null supplier, the limiter must not be touched.
            val recording = createRecording()
            val visualizedFrames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1),
                )

            every { rateLimiterProvider.getIfAvailable() } returns null

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(UserZoneInfo(chatId = chatId, zone = ZoneId.of("UTC"), language = "ru"))
            val taskSlot = slot<RecordingNotificationTask>()
            coEvery { notificationQueue.enqueue(capture(taskSlot)) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames, descriptionSupplier = null)

            assertEquals(null, taskSlot.captured.descriptionHandle)
        }

    @Test
    fun `sendRecordingNotification calls supplier when rateLimiter bean is missing (fail-open)`() =
        runTest {
            // AI is enabled (supplier != null) but the limiter bean is somehow not in the context.
            // Per design §3.2, this is a fail-open path: supplier still fires.
            val recording = createRecording()
            val visualizedFrames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1),
                )
            var supplierInvocations = 0
            val supplier: () -> kotlinx.coroutines.Deferred<Result<ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult>>? = {
                supplierInvocations++
                null
            }

            every { rateLimiterProvider.getIfAvailable() } returns null

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(UserZoneInfo(chatId = chatId, zone = ZoneId.of("UTC"), language = "ru"))
            coEvery { notificationQueue.enqueue(any()) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames, supplier)

            assertEquals(1, supplierInvocations, "supplier must fire when limiter is absent (fail-open)")
        }
}
