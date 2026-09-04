package ru.zinin.frigate.analyzer.core.facade

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.core.config.DescriptionCoroutineScope
import ru.zinin.frigate.analyzer.core.config.properties.LocalVisualizationProperties
import ru.zinin.frigate.analyzer.core.service.FrameVisualizationService
import ru.zinin.frigate.analyzer.core.service.LocalVisualizationService
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.NotificationDecision
import ru.zinin.frigate.analyzer.model.dto.NotificationDecisionReason
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import ru.zinin.frigate.analyzer.model.response.BBox
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.Detection
import ru.zinin.frigate.analyzer.model.response.ImageSize
import ru.zinin.frigate.analyzer.service.NotificationDecisionService
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import ru.zinin.frigate.analyzer.service.SavedProcessingResult
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingProcessingFacadeTest {
    private val recordingEntityService = mockk<RecordingEntityService>()
    private val telegramNotificationService = mockk<TelegramNotificationService>(relaxed = true)
    private val notificationDecisionService = mockk<NotificationDecisionService>()

    // Real service is used instead of a mock because `FrameVisualizationService.visualizeFrames`
    // has a default parameter `maxFrames = visualizationProperties.maxFrames`; the synthetic
    // `$default` bridge reads the `visualizationProperties` field directly (getfield, not a getter)
    // and would NPE on a mockk subclass mock. Same pitfall applies to `LocalVisualizationService.visualize`
    // (`lineWidth`/`quality` default to `properties.*`), so we use `spyk` over a real instance with
    // real properties and stub out the `visualize` method to skip the actual Java2D rendering path.
    private val localVisualizationService =
        spyk(LocalVisualizationService(LocalVisualizationProperties())).also {
            every { it.visualize(any(), any(), any(), any(), any(), any()) } returns ByteArray(0)
        }

    private val frameVisualizationService =
        FrameVisualizationService(
            localVisualizationService = localVisualizationService,
            visualizationProperties = LocalVisualizationProperties(),
        )

    private val recordingId = UUID.randomUUID()
    private val recording =
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
            detectionsCount = 2,
            analyzeTime = 5,
            analyzedFramesCount = 10,
            errorMessage = null,
        )

    init {
        coEvery { recordingEntityService.saveProcessingResult(any()) } returns
            SavedProcessingResult(
                recording = recording,
                detections = listOf(detectionEntity()),
            )
        coEvery { notificationDecisionService.isRecordingNotificationsGloballyEnabled() } returns true
        coEvery { notificationDecisionService.evaluate(any(), any(), any()) } returns
            NotificationDecision(shouldNotify = true, reason = NotificationDecisionReason.NEW_OBJECTS)
        coEvery { notificationDecisionService.evaluate(any(), any(), null) } returns
            NotificationDecision(shouldNotify = true, reason = NotificationDecisionReason.NEW_OBJECTS)
    }

    private fun frameWithDetection(
        idx: Int,
        confidence: Double = 0.9,
    ): FrameData =
        FrameData(
            recordId = recordingId,
            frameIndex = idx,
            frameBytes = ByteArray(1),
            detectResponse =
                DetectResponse(
                    detections = listOf(Detection(0, "person", confidence, BBox(0.0, 0.0, 1.0, 1.0))),
                    processingTime = 0,
                    imageSize = ImageSize(1, 1),
                    model = "x",
                ),
        )

    private fun detectionEntity(
        frameIndex: Int = 0,
        className: String = "person",
    ): DetectionEntity =
        DetectionEntity(
            id = UUID.randomUUID(),
            creationTimestamp = Instant.now(),
            recordingId = recordingId,
            detectionTimestamp = recording.recordTimestamp,
            frameIndex = frameIndex,
            model = "x",
            classId = 0,
            className = className,
            confidence = 0.9f,
            x1 = 0.0f,
            y1 = 0.0f,
            x2 = 1.0f,
            y2 = 1.0f,
        )

    /**
     * Мок рантайм-настроек, отдающий ответы по очереди; последний повторяется. Выключатель читается
     * дважды — при сборке supplier-а и внутри него, перед вызовом модели, — поэтому одного ответа на
     * тест мало: описание, отменённое вторым чтением, иначе не отличить от отменённого первым.
     */
    private fun runtimeSettings(vararg answers: Boolean): DescriptionRuntimeSettings {
        val queue = ArrayDeque(answers.toList())
        return mockk<DescriptionRuntimeSettings>().also { settings ->
            coEvery { settings.descriptionsEnabled() } answers { queue.removeFirstOrNull() ?: answers.last() }
        }
    }

    private fun TestScope.facade(
        agent: DescriptionAgent?,
        framesForRequest: List<FrameData> = listOf(frameWithDetection(0)),
        maxFrames: Int = 10,
        runtimeSettings: DescriptionRuntimeSettings = runtimeSettings(true),
    ): Pair<RecordingProcessingFacade, SaveProcessingResultRequest> {
        val provider = mockk<ObjectProvider<DescriptionAgent>>()
        every { provider.getIfAvailable() } returns agent
        // UnconfinedTestDispatcher wired to the TestScope's scheduler so `delay` inside describe-jobs
        // is advanced by runTest.
        val scope =
            DescriptionCoroutineScope(
                CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
            )
        val props =
            DescriptionProperties(
                enabled = agent != null,
                provider = "claude",
                common =
                    DescriptionProperties.CommonSection(
                        language = "en",
                        shortMaxLength = 200,
                        detailedMaxLength = 1500,
                        maxFrames = maxFrames,
                        queueTimeout = Duration.ofSeconds(30),
                        timeout = Duration.ofSeconds(60),
                        maxConcurrent = 2,
                    ),
            )
        // ObjectProvider, а не прямая зависимость: при application.ai.description.enabled=false
        // бина настроек нет вовсе, и обязательная инъекция сломала бы такой старт.
        val runtimeSettingsProvider = mockk<ObjectProvider<DescriptionRuntimeSettings>>()
        every { runtimeSettingsProvider.getIfAvailable() } returns runtimeSettings
        val facade =
            RecordingProcessingFacade(
                recordingEntityService = recordingEntityService,
                telegramNotificationService = telegramNotificationService,
                frameVisualizationService = frameVisualizationService,
                descriptionAgentProvider = provider,
                descriptionScope = scope,
                descriptionProperties = props,
                notificationDecisionService = notificationDecisionService,
                runtimeSettingsProvider = runtimeSettingsProvider,
            )
        return facade to SaveProcessingResultRequest(recordingId = recordingId, frames = framesForRequest)
    }

    private suspend fun captureSupplierDuring(block: suspend () -> Unit): (() -> Deferred<Result<DescriptionResult>>)? {
        var captured: (() -> Deferred<Result<DescriptionResult>>)? = null
        coEvery {
            telegramNotificationService.sendRecordingNotification(any(), any(), any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            captured = thirdArg<Any?>() as? (() -> Deferred<Result<DescriptionResult>>)
            Unit
        }
        block()
        return captured
    }

    @Test
    fun `recording notification settings failure happens before processed state is saved`() =
        runTest {
            val (f, req) = facade(agent = null)
            coEvery { notificationDecisionService.isRecordingNotificationsGloballyEnabled() } throws
                RuntimeException("settings db down")

            assertFailsWith<RuntimeException> {
                f.processAndNotify(req)
            }

            coVerify(exactly = 0) { recordingEntityService.saveProcessingResult(any()) }
        }

    @Test
    fun `post-save recording and detection reads are not required to send notification`() =
        runTest {
            val savedDetections = listOf(detectionEntity())

            coEvery { recordingEntityService.saveProcessingResult(any()) } returns
                SavedProcessingResult(
                    recording = recording,
                    detections = savedDetections,
                )
            coEvery { recordingEntityService.getRecording(recordingId) } throws
                AssertionError("post-save recording read must not be called")
            coEvery {
                notificationDecisionService.evaluate(recording, savedDetections, true)
            } returns
                NotificationDecision(
                    shouldNotify = true,
                    reason = NotificationDecisionReason.NEW_OBJECTS,
                )

            val (f, req) = facade(agent = null)

            f.processAndNotify(req)

            coVerify(exactly = 1) {
                notificationDecisionService.evaluate(recording, savedDetections, true)
            }
            coVerify(exactly = 1) {
                telegramNotificationService.sendRecordingNotification(recording, any(), null)
            }
            coVerify(exactly = 0) { recordingEntityService.getRecording(any()) }
        }

    @Test
    fun `agent disabled produces null supplier`() =
        runTest {
            val (f, req) = facade(agent = null)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            assertNull(supplier)
        }

    @Test
    fun `agent enabled but empty frames produces null supplier`() =
        runTest {
            val agent = mockk<DescriptionAgent>()
            val (f, req) = facade(agent, framesForRequest = emptyList())
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            assertNull(supplier)
        }

    @Test
    fun `agent enabled with frames produces non-null supplier that starts describe lazily`() =
        runTest {
            val agent = mockk<DescriptionAgent>()
            coEvery { agent.describe(any()) } coAnswers { DescriptionResult("s", "d") }

            val (f, req) = facade(agent)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            assertNotNull(supplier)
            // supplier has not been invoked yet — describe must not have been called
            coVerify(exactly = 0) { agent.describe(any()) }
            val handle = supplier.invoke()
            assertNotNull(handle)
            val outcome = handle.await()
            assertEquals(DescriptionResult("s", "d"), outcome.getOrNull())
        }

    @Test
    fun `exception in describe is captured in Result_failure, does not break facade`() =
        runTest {
            val agent = mockk<DescriptionAgent>()
            coEvery { agent.describe(any()) } coAnswers {
                delay(1)
                throw IllegalStateException("boom")
            }

            val (f, req) = facade(agent)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            val outcome = supplier!!.invoke().await()
            assertEquals(true, outcome.isFailure)
        }

    @Test
    fun `frame limit 10 is applied when more frames present`() =
        runTest {
            val manyFrames = (0..49).map { frameWithDetection(it) }
            val agent = mockk<DescriptionAgent>()
            val captured = slot<DescriptionRequest>()
            coEvery { agent.describe(capture(captured)) } coAnswers { DescriptionResult("s", "d") }

            val (f, req) = facade(agent, framesForRequest = manyFrames)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            supplier!!.invoke().await()

            assertEquals(10, captured.captured.frames.size)
            assertEquals((0..9).toList(), captured.captured.frames.map { it.frameIndex })
        }

    @Test
    fun `frame selection matches visualization ranking (confidence then count), then chronological in prompt`() =
        runTest {
            // 5 frames with mixed confidence; with maxFrames=3 the AI must receive the top-3 by
            // confidence (same ranking as FrameVisualizationService), ordered chronologically in prompt.
            val frames =
                listOf(
                    frameWithDetection(0, confidence = 0.5),
                    frameWithDetection(1, confidence = 0.9),
                    frameWithDetection(2, confidence = 0.3),
                    frameWithDetection(3, confidence = 0.7),
                    frameWithDetection(4, confidence = 0.1),
                )
            val agent = mockk<DescriptionAgent>()
            val captured = slot<DescriptionRequest>()
            coEvery { agent.describe(capture(captured)) } coAnswers { DescriptionResult("s", "d") }

            val (f, req) = facade(agent, framesForRequest = frames, maxFrames = 3)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            supplier!!.invoke().await()

            // Top-3 by confidence: indices 1 (0.9), 3 (0.7), 0 (0.5). Then sorted chronologically: 0, 1, 3.
            assertEquals(listOf(0, 1, 3), captured.captured.frames.map { it.frameIndex })
        }

    @Test
    fun `frames without detections are filtered out for description`() =
        runTest {
            val framesMix =
                listOf(
                    FrameData(recordingId, 0, ByteArray(1)), // no detections — filtered
                    frameWithDetection(1),
                    FrameData(recordingId, 2, ByteArray(1)), // no detections — filtered
                    frameWithDetection(3),
                )
            val agent = mockk<DescriptionAgent>()
            val captured = slot<DescriptionRequest>()
            coEvery { agent.describe(capture(captured)) } coAnswers { DescriptionResult("s", "d") }

            val (f, req) = facade(agent, framesForRequest = framesMix)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            supplier!!.invoke().await()

            assertEquals(2, captured.captured.frames.size)
            assertEquals(listOf(1, 3), captured.captured.frames.map { it.frameIndex })
        }

    @Test
    fun `the runtime switch keeps the description supplier out`() =
        runTest {
            val (f, req) = facade(agent = mockk(relaxed = true), runtimeSettings = runtimeSettings(false))

            val supplier = captureSupplierDuring { f.processAndNotify(req) }

            assertNull(supplier)
            // Уведомление обязано уйти и без описания: выключатель решает лишь, обогащать ли его.
            // Без этой проверки assertNull прошёл бы и в случае, когда уведомления не было вовсе.
            coVerify(exactly = 1) { telegramNotificationService.sendRecordingNotification(recording, any(), null) }
        }

    @Test
    fun `the runtime switch on leaves the supplier in place`() =
        runTest {
            val (f, req) = facade(agent = mockk(relaxed = true), runtimeSettings = runtimeSettings(true))

            val supplier = captureSupplierDuring { f.processAndNotify(req) }

            assertNotNull(supplier)
        }

    /**
     * Вторая проверка выключателя — внутри supplier-а. Между его сборкой и вызовом лежат фильтрация
     * получателей и rate limiter, поэтому одной проверки хватало бы лишь на «подействует со следующей
     * записи»; обещание дизайна — «выключение отменяет все описания, которые ещё не начались».
     */
    @Test
    fun `switching off between building and invoking the supplier skips the model call`() =
        runTest {
            val agent = mockk<DescriptionAgent>()
            coEvery { agent.describe(any()) } coAnswers { DescriptionResult("s", "d") }

            val (f, req) = facade(agent, runtimeSettings = runtimeSettings(true, false))
            val supplier = captureSupplierDuring { f.processAndNotify(req) }

            val outcome = assertNotNull(supplier).invoke().await()

            coVerify(exactly = 0) { agent.describe(any()) }
            // Deferred обязан быть, причём завершиться: слот лимитера уже потрачен, а на той стороне
            // сообщение уже ушло с плейсхолдером — его правит DescriptionState.Failed, а не отмена.
            assertTrue(outcome.isFailure)
        }

    /**
     * Гейт стоит ПОСЛЕ saveProcessingResult: запись уже помечена обработанной, поэтому исключение из
     * чтения ключа потеряло бы уведомление без повтора. Нечитаемый ключ трактуется как отсутствующий,
     * то есть `true`.
     */
    @Test
    fun `an unreadable switch fails open and keeps the notification`() =
        runTest {
            val settings = mockk<DescriptionRuntimeSettings>()
            coEvery { settings.descriptionsEnabled() } throws RuntimeException("settings db down")

            val (f, req) = facade(agent = mockk(relaxed = true), runtimeSettings = settings)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }

            assertNotNull(supplier)
            coVerify(exactly = 1) { telegramNotificationService.sendRecordingNotification(recording, any(), any()) }
        }
}
