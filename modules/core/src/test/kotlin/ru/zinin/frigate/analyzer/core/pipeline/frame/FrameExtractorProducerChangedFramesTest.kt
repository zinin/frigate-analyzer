package ru.zinin.frigate.analyzer.core.pipeline.frame

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.core.config.properties.PipelineProperties
import ru.zinin.frigate.analyzer.core.service.DetectService
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.response.ExtractedFrameData
import ru.zinin.frigate.analyzer.model.response.FrameExtractionResponse
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.UUID

/** Продюсер передаёт консьюмерам, какие кадры изменились, и называет исход сравнения в логе. */
class FrameExtractorProducerChangedFramesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `frames the detector marks are sent as changed`() =
        runTest {
            val video = Files.write(dir.resolve("00.00.mp4"), byteArrayOf(1))
            val recordingEntityService = mockk<RecordingEntityService>()
            coEvery { recordingEntityService.findUnprocessedRecordings(any()) } returns listOf(recording(video)) andThen
                emptyList()
            // spyk: значения по умолчанию extractFramesRemoteWithRetry берутся из настоящих настроек.
            val detectService = spyk(DetectService(mockk(), mockk(), DetectProperties(), mockk(), mockk()))
            coEvery {
                detectService.extractFramesRemoteWithRetry(any(), any(), any(), any(), any(), any(), any(), any())
            } returns extraction(frames = 2)
            val detector = mockk<ChangedFrameDetector>()
            every { detector.detect(any()) } returns ChangedFrames(indexes = setOf(1))
            val producer =
                FrameExtractorProducer(
                    detectService,
                    recordingEntityService,
                    RecordingTracker(),
                    PipelineProperties(),
                    DetectProperties(),
                    detector,
                )
            val channel = Channel<FrameTask>(Channel.UNLIMITED)

            val job = launch { producer.produce(channel, 0) }
            val tasks = listOf(channel.receive(), channel.receive())
            job.cancel()

            assertThat(tasks.map { it.frameIndex }).containsExactly(0, 1)
            assertThat(tasks.map { it.changed }).containsExactly(false, true)
        }

    @Test
    fun `the log suffix names the outcome and what the comparison cost`() {
        assertThat(changedFramesSuffix(ChangedFrames(indexes = setOf(2)), total = 4, millis = 240))
            .isEqualTo("; changed 1/3 -> good model (240 ms)")
        assertThat(changedFramesSuffix(ChangedFrames(skip = ChangedFrames.Skip.MONOCHROME), total = 4, millis = 40))
            .isEqualTo("; monochrome, changed-frame check skipped (40 ms)")
        assertThat(changedFramesSuffix(ChangedFrames(skip = ChangedFrames.Skip.FAILED), total = 4, millis = 3))
            .isEqualTo("; changed-frame check failed (3 ms)")
        assertThat(changedFramesSuffix(ChangedFrames(skip = ChangedFrames.Skip.DISABLED), total = 4, millis = 0))
            .isEmpty()
        assertThat(changedFramesSuffix(ChangedFrames(skip = ChangedFrames.Skip.NOTHING_TO_COMPARE), total = 1, millis = 0))
            .isEmpty()
    }

    private fun extraction(frames: Int) =
        FrameExtractionResponse(
            success = true,
            videoDuration = 10.0,
            videoResolution = listOf(2560, 1920),
            framesExtracted = frames,
            frames =
                List(frames) {
                    ExtractedFrameData(
                        frameNumber = it,
                        timestamp = it * 4.0,
                        imageBase64 = Base64.getEncoder().encodeToString(byteArrayOf(it.toByte())),
                        width = 2560,
                        height = 1920,
                        reason = if (it == 0) "first" else "grid",
                    )
                },
            processingTimeMs = 1,
        )

    private fun recording(video: Path) =
        RecordingDto(
            id = UUID.randomUUID(),
            creationTimestamp = Instant.EPOCH,
            filePath = video.toString(),
            fileCreationTimestamp = Instant.EPOCH,
            camId = "cam1",
            recordDate = LocalDate.EPOCH,
            recordTime = LocalTime.MIDNIGHT,
            recordTimestamp = Instant.EPOCH,
            startProcessingTimestamp = null,
            processTimestamp = null,
            processAttempts = 0,
            detectionsCount = 0,
            analyzeTime = 0,
            analyzedFramesCount = 0,
            errorMessage = null,
        )
}
