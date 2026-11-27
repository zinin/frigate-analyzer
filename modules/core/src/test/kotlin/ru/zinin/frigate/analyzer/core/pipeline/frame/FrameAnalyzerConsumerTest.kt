package ru.zinin.frigate.analyzer.core.pipeline.frame

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.core.facade.RecordingProcessingFacade
import ru.zinin.frigate.analyzer.core.service.DetectService
import ru.zinin.frigate.analyzer.core.service.DetectionPostProcessor
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.ImageSize
import java.util.UUID

/** Изменившийся кадр консьюмер отдаёт сразу good-model, остальные идут прежним каскадом. */
class FrameAnalyzerConsumerTest {
    private val recordId = UUID.randomUUID()
    private val bytes = byteArrayOf(1)
    private val detectService = spyk(DetectService(mockk(), mockk(), DetectProperties(), mockk(), mockk()))
    private val recordingTracker = mockk<RecordingTracker>()
    private val postProcessor = mockk<DetectionPostProcessor>()
    private val consumer =
        FrameAnalyzerConsumer(detectService, recordingTracker, mockk<RecordingProcessingFacade>(), postProcessor)

    @Test
    fun `a changed frame goes to the good model`() =
        runTest {
            val fromGoodModel = response("yolo26x.pt")
            coEvery { detectService.detectWithRetry(any(), any(), any(), any(), any()) } returns response("yolo26s.pt")
            coEvery { postProcessor.process(any(), any(), any(), any()) } returns response("yolo26s.pt")
            coEvery { postProcessor.processChanged(bytes, 1, 0) } returns fromGoodModel
            every { recordingTracker.markCompleted(any(), any(), any()) } returns false

            consume(FrameTask(recordId, 1, bytes, changed = true))

            coVerify(exactly = 1) { postProcessor.processChanged(bytes, 1, 0) }
            coVerify(exactly = 0) { detectService.detectWithRetry(any(), any(), any(), any(), any()) }
            verify { recordingTracker.markCompleted(recordId, 1, fromGoodModel) }
        }

    @Test
    fun `an unchanged frame keeps the cascade`() =
        runTest {
            val initial = response("yolo26s.pt")
            val final = response("yolo26x.pt")
            coEvery { detectService.detectWithRetry(any(), any(), any(), any(), any()) } returns initial
            coEvery { postProcessor.process(bytes, initial, 0, 0) } returns final
            every { recordingTracker.markCompleted(any(), any(), any()) } returns false

            consume(FrameTask(recordId, 0, bytes))

            coVerify(exactly = 0) { postProcessor.processChanged(any(), any(), any()) }
            verify { recordingTracker.markCompleted(recordId, 0, final) }
        }

    private suspend fun consume(task: FrameTask) {
        val channel = Channel<FrameTask>(1)
        channel.send(task)
        channel.close()
        consumer.consume(channel, consumerId = 0)
    }

    private fun response(model: String) = DetectResponse(emptyList(), 1, ImageSize(2560, 1920), model)
}
