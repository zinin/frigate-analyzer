package ru.zinin.frigate.analyzer.core.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.core.config.properties.DetectionFilterProperties
import ru.zinin.frigate.analyzer.core.service.impl.DetectionFilterServiceImpl
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import ru.zinin.frigate.analyzer.model.response.BBox
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.Detection
import ru.zinin.frigate.analyzer.model.response.ImageSize

/** Изменившийся кадр идёт сразу в good-model; если она не отвечает, кадр получает результат малой модели без перепроверки. */
class DetectionPostProcessorTest {
    private val properties =
        DetectProperties(defaultModel = "small.pt", goodModel = "good.pt", defaultConfidence = 0.45, defaultImgSize = 1280)

    // spyk, а не mockk: значения параметров по умолчанию `detectWithRetry` берутся из настоящих полей.
    private val detectService = spyk(DetectService(mockk(), mockk(), properties, mockk(), mockk()))
    private val processor =
        DetectionPostProcessor(
            detectService,
            DetectionFilterServiceImpl(DetectionFilterProperties(allowedClasses = listOf("car", "person"))),
            properties,
        )
    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun `a changed frame is analyzed by the good model only`() =
        runTest {
            coEvery { detectService.detectWithRetry(any(), any(), any(), "good.pt", any()) } returns
                response("good.pt", "car", "bench")
            coEvery { detectService.detectWithRetry(any(), any(), any(), "small.pt", any()) } returns
                response("small.pt", "person")

            val result = processor.processChanged(bytes, frameIndex = 1, consumerId = 0)

            assertThat(result.model).isEqualTo("good.pt")
            assertThat(result.detections.map { it.className }).containsExactly("car")
            coVerify(exactly = 1) { detectService.detectWithRetry(any(), any(), any(), any(), any()) }
            // Те же conf и imgsz, что и всегда: иначе параллельные вызовы vision-api подменят их друг другу.
            coVerify {
                detectService.detectWithRetry(
                    bytes,
                    properties.defaultConfidence,
                    properties.defaultImgSize,
                    "good.pt",
                    any(),
                )
            }
        }

    @Test
    fun `a good model timeout falls back to the default model without a re-check`() =
        runTest {
            coEvery { detectService.detectWithRetry(any(), any(), any(), "good.pt", any()) } throws
                DetectTimeoutException("slow")
            coEvery { detectService.detectWithRetry(any(), any(), any(), "small.pt", any()) } returns
                response("small.pt", "person")

            val result = processor.processChanged(bytes, frameIndex = 1, consumerId = 0)

            // Перепроверка ждала бы ещё один полный таймаут ту же good-model, которая только что не ответила.
            assertThat(result.model).isEqualTo("small.pt")
            assertThat(result.detections.map { it.className }).containsExactly("person")
            coVerify(exactly = 1) { detectService.detectWithRetry(any(), any(), any(), "good.pt", any()) }
            coVerify(exactly = 1) { detectService.detectWithRetry(any(), any(), any(), "small.pt", any()) }
        }

    @Test
    fun `a good model failure falls back to the default model`() =
        runTest {
            coEvery { detectService.detectWithRetry(any(), any(), any(), "good.pt", any()) } throws
                IllegalStateException("no server")
            coEvery { detectService.detectWithRetry(any(), any(), any(), "small.pt", any()) } returns
                response("small.pt", "bench")

            val result = processor.processChanged(bytes, frameIndex = 1, consumerId = 0)

            assertThat(result.model).isEqualTo("small.pt")
            assertThat(result.detections).isEmpty()
            coVerify(exactly = 1) { detectService.detectWithRetry(any(), any(), any(), "good.pt", any()) }
            coVerify(exactly = 1) { detectService.detectWithRetry(any(), any(), any(), "small.pt", any()) }
            coVerify {
                detectService.detectWithRetry(
                    bytes,
                    properties.defaultConfidence,
                    properties.defaultImgSize,
                    "small.pt",
                    any(),
                )
            }
        }

    @Test
    fun `cancellation is not swallowed`() =
        runTest {
            coEvery { detectService.detectWithRetry(any(), any(), any(), "good.pt", any()) } throws
                CancellationException("shutdown")
            coEvery { detectService.detectWithRetry(any(), any(), any(), "small.pt", any()) } returns
                response("small.pt", "car")

            val error = runCatching { processor.processChanged(bytes, frameIndex = 1, consumerId = 0) }.exceptionOrNull()

            assertThat(error).isInstanceOf(CancellationException::class.java)
            coVerify(exactly = 0) { detectService.detectWithRetry(any(), any(), any(), "small.pt", any()) }
        }

    private fun response(
        model: String,
        vararg classes: String,
    ) = DetectResponse(
        detections = classes.map { Detection(0, it, 0.9, BBox(1.0, 1.0, 2.0, 2.0)) },
        processingTime = 1,
        imageSize = ImageSize(2560, 1920),
        model = model,
    )
}
