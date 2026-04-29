package ru.zinin.frigate.analyzer.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.persistent.RecordingEntity
import ru.zinin.frigate.analyzer.model.request.CreateDetectionRequest
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import ru.zinin.frigate.analyzer.model.response.BBox
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.Detection
import ru.zinin.frigate.analyzer.model.response.ImageSize
import ru.zinin.frigate.analyzer.service.DetectionEntityService
import ru.zinin.frigate.analyzer.service.mapper.RecordingMapper
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class RecordingEntityServiceImplTest {
    private val mapper = mockk<RecordingMapper>()
    private val repository = mockk<RecordingEntityRepository>()
    private val uuidGeneratorHelper = mockk<UUIDGeneratorHelper>()
    private val detectionService = mockk<DetectionEntityService>()
    private val now = Instant.parse("2026-04-29T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val service =
        RecordingEntityServiceImpl(
            mapper = mapper,
            repository = repository,
            uuidGeneratorHelper = uuidGeneratorHelper,
            clock = clock,
            detectionService = detectionService,
        )

    @Test
    fun `saveProcessingResult returns saved recording snapshot and created detections`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val recordingTimestamp = Instant.parse("2026-04-29T11:59:00Z")
            val recording = recordingEntity(recordingId, recordingTimestamp)
            val savedDetection = detectionEntity(recordingId, recordingTimestamp)
            val mappedRecordings = mutableListOf<RecordingEntity>()

            coEvery { repository.findById(recordingId) } returns recording
            coEvery { detectionService.createDetection(any()) } returns savedDetection
            coEvery { repository.markProcessed(recordingId, now, 1, 123, 1) } returns 1L
            every { mapper.toDto(capture(mappedRecordings)) } answers { mappedRecordings.last().toDto() }

            val result =
                service.saveProcessingResult(
                    SaveProcessingResultRequest(
                        recordingId = recordingId,
                        frames = listOf(frameWithDetection(recordingId)),
                    ),
                )

            assertEquals(listOf(savedDetection), result.detections)
            assertEquals(now, result.recording.processTimestamp)
            assertEquals(4, result.recording.processAttempts)
            assertEquals(1, result.recording.detectionsCount)
            assertEquals(130, result.recording.analyzeTime)
            assertEquals(1, result.recording.analyzedFramesCount)
            coVerify(exactly = 1) {
                detectionService.createDetection(
                    CreateDetectionRequest(
                        recordingId = recordingId,
                        detectionTimestamp = recordingTimestamp,
                        frameIndex = 4,
                        model = "model-a",
                        classId = 7,
                        className = "person",
                        confidence = 0.9f,
                        x1 = 0.1f,
                        y1 = 0.2f,
                        x2 = 0.8f,
                        y2 = 0.9f,
                    ),
                )
            }
            coVerify(exactly = 1) { repository.markProcessed(recordingId, now, 1, 123, 1) }
        }

    private fun recordingEntity(
        recordingId: UUID,
        recordingTimestamp: Instant,
    ) = RecordingEntity(
        id = recordingId,
        creationTimestamp = now,
        filePath = "/recordings/camera1/2026-04-29/video.mp4",
        fileCreationTimestamp = now,
        camId = "camera1",
        recordDate = LocalDate.of(2026, 4, 29),
        recordTime = LocalTime.of(11, 59),
        recordTimestamp = recordingTimestamp,
        startProcessingTimestamp = now,
        processTimestamp = null,
        processAttempts = 3,
        detectionsCount = 0,
        analyzeTime = 7,
        analyzedFramesCount = 0,
        errorMessage = null,
    )

    private fun detectionEntity(
        recordingId: UUID,
        recordingTimestamp: Instant,
    ) = DetectionEntity(
        id = UUID.randomUUID(),
        creationTimestamp = now,
        recordingId = recordingId,
        detectionTimestamp = recordingTimestamp,
        frameIndex = 4,
        model = "model-a",
        classId = 7,
        className = "person",
        confidence = 0.9f,
        x1 = 0.1f,
        y1 = 0.2f,
        x2 = 0.8f,
        y2 = 0.9f,
    )

    private fun frameWithDetection(recordingId: UUID) =
        FrameData(
            recordId = recordingId,
            frameIndex = 4,
            frameBytes = ByteArray(1),
            detectResponse =
                DetectResponse(
                    detections =
                        listOf(
                            Detection(
                                classId = 7,
                                className = "person",
                                confidence = 0.9,
                                bbox = BBox(0.1, 0.2, 0.8, 0.9),
                            ),
                        ),
                    processingTime = 123,
                    imageSize = ImageSize(1, 1),
                    model = "model-a",
                ),
        )

    private fun RecordingEntity.toDto() =
        RecordingDto(
            id = requireNotNull(id),
            creationTimestamp = requireNotNull(creationTimestamp),
            filePath = requireNotNull(filePath),
            fileCreationTimestamp = requireNotNull(fileCreationTimestamp),
            camId = requireNotNull(camId),
            recordDate = requireNotNull(recordDate),
            recordTime = requireNotNull(recordTime),
            recordTimestamp = requireNotNull(recordTimestamp),
            startProcessingTimestamp = startProcessingTimestamp,
            processTimestamp = processTimestamp,
            processAttempts = requireNotNull(processAttempts),
            detectionsCount = requireNotNull(detectionsCount),
            analyzeTime = requireNotNull(analyzeTime),
            analyzedFramesCount = requireNotNull(analyzedFramesCount),
            errorMessage = errorMessage,
        )
}
