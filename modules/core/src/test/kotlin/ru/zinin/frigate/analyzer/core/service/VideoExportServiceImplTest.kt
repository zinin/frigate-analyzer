package ru.zinin.frigate.analyzer.core.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.core.config.properties.DetectionFilterProperties
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.helper.VideoMergeHelper
import ru.zinin.frigate.analyzer.model.persistent.RecordingEntity
import ru.zinin.frigate.analyzer.model.response.JobStatusResponse
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress.Stage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoExportServiceImplTest {
    @TempDir
    lateinit var tempDir: Path

    private val recordingRepository = mockk<RecordingEntityRepository>()
    private val videoMergeHelper = mockk<VideoMergeHelper>()
    private val tempFileHelper = mockk<TempFileHelper>()
    private val videoVisualizationService = mockk<VideoVisualizationService>()
    private val detectProperties = DetectProperties(goodModel = "yolo26x.pt")
    private val detectionFilterProperties = DetectionFilterProperties(allowedClasses = listOf("person", "car"))

    private val service =
        VideoExportServiceImpl(
            recordingRepository = recordingRepository,
            videoMergeHelper = videoMergeHelper,
            tempFileHelper = tempFileHelper,
            videoVisualizationService = videoVisualizationService,
            detectProperties = detectProperties,
            detectionFilterProperties = detectionFilterProperties,
        )

    private val start: Instant = Instant.parse("2026-02-19T10:00:00Z")
    private val end: Instant = Instant.parse("2026-02-19T11:00:00Z")
    private val camId = "front"

    private fun createTempFile(name: String): Path {
        val path = tempDir.resolve(name)
        Files.write(path, byteArrayOf(1, 2, 3))
        return path
    }

    private fun recording(filePath: String?) =
        RecordingEntity(
            id = UUID.randomUUID(),
            creationTimestamp = null,
            filePath = filePath,
            fileCreationTimestamp = null,
            camId = "front",
            recordDate = null,
            recordTime = null,
            recordTimestamp = null,
            startProcessingTimestamp = null,
            processTimestamp = null,
            processAttempts = null,
            detectionsCount = null,
            analyzeTime = null,
            analyzedFramesCount = null,
        )

    @Test
    fun `export original emits PREPARING MERGING and returns merged path`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile

            val progress = mutableListOf<VideoExportProgress>()

            val result =
                service.exportVideo(
                    startInstant = start,
                    endInstant = end,
                    camId = camId,
                    mode = ExportMode.ORIGINAL,
                    onProgress = { progress.add(it) },
                )

            assertEquals(mergedFile, result)
            assertTrue(progress.size >= 2)
            assertEquals(Stage.PREPARING, progress[0].stage)
            assertEquals(Stage.MERGING, progress[1].stage)
            // No COMPRESSING because file is small
            assertFalse(progress.any { it.stage == Stage.COMPRESSING })
        }

    @Test
    fun `export original emits COMPRESSING when threshold exceeded`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged-large.mp4")
            val compressedFile = createTempFile("compressed.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { videoMergeHelper.compressVideo(mergedFile) } returns compressedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true

            mockkStatic(Files::class)
            try {
                every { Files.exists(any<Path>()) } returns true
                every { Files.size(mergedFile) } returns VideoMergeHelper.COMPRESS_THRESHOLD_BYTES + 1
                every { Files.size(compressedFile) } returns VideoMergeHelper.MAX_FILE_SIZE_BYTES - 1

                val progress = mutableListOf<VideoExportProgress>()

                val result =
                    service.exportVideo(
                        startInstant = start,
                        endInstant = end,
                        camId = camId,
                        mode = ExportMode.ORIGINAL,
                        onProgress = { progress.add(it) },
                    )

                assertEquals(compressedFile, result)
                assertEquals(Stage.PREPARING, progress[0].stage)
                assertEquals(Stage.MERGING, progress[1].stage)
                assertEquals(Stage.COMPRESSING, progress[2].stage)
                coVerify { tempFileHelper.deleteIfExists(mergedFile) }
            } finally {
                unmockkStatic(Files::class)
            }
        }

    @Test
    fun `export throws when no recordings`() =
        runTest {
            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns emptyList()

            val exception =
                assertThrows<IllegalStateException> {
                    service.exportVideo(
                        startInstant = start,
                        endInstant = end,
                        camId = camId,
                    )
                }

            assertTrue(exception.message!!.contains("No recordings found"))
        }

    @Test
    fun `export throws when all files missing`() =
        runTest {
            val missingPath = tempDir.resolve("does-not-exist.mp4").toString()

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(missingPath))

            val exception =
                assertThrows<IllegalStateException> {
                    service.exportVideo(
                        startInstant = start,
                        endInstant = end,
                        camId = camId,
                    )
                }

            assertTrue(exception.message!!.contains("All recording files are missing from disk"))
        }

    @Test
    fun `annotated mode calls VideoVisualizationService with goodModel and allowed classes`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")
            val annotatedFile = createTempFile("annotated.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
            coEvery {
                videoVisualizationService.annotateVideo(
                    videoPath = mergedFile,
                    classes = "person,car",
                    model = "yolo26x.pt",
                    onProgress = any(),
                )
            } returns annotatedFile

            val result =
                service.exportVideo(
                    startInstant = start,
                    endInstant = end,
                    camId = camId,
                    mode = ExportMode.ANNOTATED,
                )

            assertEquals(annotatedFile, result)
            coVerify {
                videoVisualizationService.annotateVideo(
                    videoPath = mergedFile,
                    classes = "person,car",
                    model = "yolo26x.pt",
                    onProgress = any(),
                )
            }
        }

    @Test
    fun `annotated mode emits ANNOTATING progress`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")
            val annotatedFile = createTempFile("annotated.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
            coEvery {
                videoVisualizationService.annotateVideo(
                    videoPath = mergedFile,
                    classes = "person,car",
                    model = "yolo26x.pt",
                    onProgress = any(),
                )
            } coAnswers {
                // Simulate progress callbacks from the visualization service
                val onProgress = lastArg<suspend (JobStatusResponse) -> Unit>()
                onProgress(mockk<JobStatusResponse> { every { progress } returns 50 })
                onProgress(mockk<JobStatusResponse> { every { progress } returns 100 })
                annotatedFile
            }

            val progress = mutableListOf<VideoExportProgress>()

            service.exportVideo(
                startInstant = start,
                endInstant = end,
                camId = camId,
                mode = ExportMode.ANNOTATED,
                onProgress = { progress.add(it) },
            )

            // Should contain PREPARING, MERGING, ANNOTATING(0%), ANNOTATING(50%), ANNOTATING(100%)
            assertTrue(progress.any { it.stage == Stage.ANNOTATING && it.percent == 0 })
            assertTrue(progress.any { it.stage == Stage.ANNOTATING && it.percent == 50 })
            assertTrue(progress.any { it.stage == Stage.ANNOTATING && it.percent == 100 })
        }

    @Test
    fun `annotated mode deletes intermediate merged file after success`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")
            val annotatedFile = createTempFile("annotated.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
            coEvery {
                videoVisualizationService.annotateVideo(
                    videoPath = mergedFile,
                    classes = "person,car",
                    model = "yolo26x.pt",
                    onProgress = any(),
                )
            } returns annotatedFile

            service.exportVideo(
                startInstant = start,
                endInstant = end,
                camId = camId,
                mode = ExportMode.ANNOTATED,
            )

            coVerify { tempFileHelper.deleteIfExists(mergedFile) }
        }

    @Test
    fun `annotated mode deletes intermediate merged file on annotation error and rethrows`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
            coEvery {
                videoVisualizationService.annotateVideo(
                    videoPath = mergedFile,
                    classes = "person,car",
                    model = "yolo26x.pt",
                    onProgress = any(),
                )
            } throws RuntimeException("GPU out of memory")

            val exception =
                assertThrows<RuntimeException> {
                    service.exportVideo(
                        startInstant = start,
                        endInstant = end,
                        camId = camId,
                        mode = ExportMode.ANNOTATED,
                    )
                }

            assertEquals("GPU out of memory", exception.message)
            // The merged file should be cleaned up (called from annotate catch + outer catch)
            coVerify(atLeast = 1) { tempFileHelper.deleteIfExists(mergedFile) }
        }
}
