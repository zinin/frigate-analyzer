package ru.zinin.frigate.analyzer.core.facade

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.core.config.DescriptionCoroutineScope
import ru.zinin.frigate.analyzer.core.config.properties.LocalVisualizationProperties
import ru.zinin.frigate.analyzer.core.service.FrameVisualizationService
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingProcessingFacadeTest {
    private val recordingEntityService = mockk<RecordingEntityService>()
    private val telegramNotificationService = mockk<TelegramNotificationService>(relaxed = true)

    // Real service is used instead of a mock because `FrameVisualizationService.visualizeFrames`
    // has a default parameter `maxFrames = visualizationProperties.maxFrames`; the synthetic
    // `$default` bridge reads the `visualizationProperties` field directly (getfield, not a getter)
    // and would NPE on a mockk subclass mock. Real service with a mocked LocalVisualizationService
    // and real `LocalVisualizationProperties` sidesteps that entirely: when `request.frames` contain
    // no detections, `visualizeFrames` returns an empty list without ever calling the visualizer.
    private val frameVisualizationService =
        FrameVisualizationService(
            localVisualizationService = mockk(relaxed = true),
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
        coEvery { recordingEntityService.saveProcessingResult(any()) } returns Unit
        coEvery { recordingEntityService.getRecording(recordingId) } returns recording
    }

    private fun TestScope.facade(
        agent: DescriptionAgent?,
        framesForRequest: List<FrameData> = listOf(FrameData(recordingId, 0, ByteArray(1))),
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
                        maxFrames = 10,
                        queueTimeout = Duration.ofSeconds(30),
                        timeout = Duration.ofSeconds(60),
                        maxConcurrent = 2,
                    ),
            )
        val facade =
            RecordingProcessingFacade(
                recordingEntityService = recordingEntityService,
                telegramNotificationService = telegramNotificationService,
                frameVisualizationService = frameVisualizationService,
                descriptionAgentProvider = provider,
                descriptionScope = scope,
                descriptionProperties = props,
            )
        return facade to SaveProcessingResultRequest(recordingId = recordingId, frames = framesForRequest)
    }

    private suspend fun captureSupplierDuring(block: suspend () -> Unit): (() -> Deferred<Result<DescriptionResult>>?)? {
        var captured: (() -> Deferred<Result<DescriptionResult>>?)? = null
        coEvery {
            telegramNotificationService.sendRecordingNotification(any(), any(), any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            captured = thirdArg<Any?>() as? (() -> Deferred<Result<DescriptionResult>>?)
            Unit
        }
        block()
        return captured
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
            val outcome = supplier!!.invoke()!!.await()
            assertEquals(true, outcome.isFailure)
        }

    @Test
    fun `frame limit 10 is applied when more frames present`() =
        runTest {
            val manyFrames = (0..49).map { FrameData(recordingId, it, ByteArray(1)) }
            val agent = mockk<DescriptionAgent>()
            val captured = slot<DescriptionRequest>()
            coEvery { agent.describe(capture(captured)) } coAnswers { DescriptionResult("s", "d") }

            val (f, req) = facade(agent, framesForRequest = manyFrames)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            supplier!!.invoke()!!.await()

            assertEquals(10, captured.captured.frames.size)
            assertEquals((0..9).toList(), captured.captured.frames.map { it.frameIndex })
        }
}
