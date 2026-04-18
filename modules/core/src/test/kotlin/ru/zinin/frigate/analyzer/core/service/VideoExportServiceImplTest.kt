package ru.zinin.frigate.analyzer.core.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.core.config.properties.DetectionFilterProperties
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.helper.VideoMergeHelper
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import ru.zinin.frigate.analyzer.model.persistent.RecordingEntity
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress.Stage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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
    private val detectProperties = DetectProperties(goodModel = "yolo26x.pt")
    private val videoVisualizationService =
        spyk(
            VideoVisualizationService(
                detectService = mockk(),
                loadBalancer = mockk(),
                detectProperties = detectProperties,
            ),
        )
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
    private val recordingId: UUID = UUID.randomUUID()
    private val recordTimestamp: Instant = Instant.parse("2026-02-19T10:30:00Z")
    private val exportDuration: Duration = Duration.ofMinutes(1)

    private fun createTempFile(name: String): Path {
        val path = tempDir.resolve(name)
        Files.write(path, byteArrayOf(1, 2, 3))
        return path
    }

    private data class AnnotateCall(
        val videoPath: Path,
        val classes: String?,
        val model: String,
    )

    private var lastAnnotateCall: AnnotateCall? = null

    private fun stubAnnotateVideo(returns: Path) {
        coEvery {
            videoVisualizationService.annotateVideo(
                videoPath = any(),
                conf = any(),
                imgSize = any(),
                maxDet = any(),
                detectEvery = any(),
                classes = any(),
                lineWidth = any(),
                showLabels = any(),
                showConf = any(),
                model = any(),
                onProgress = any(),
                onJobSubmitted = any(),
            )
        } coAnswers {
            lastAnnotateCall = AnnotateCall(firstArg(), arg(5), arg(9))
            returns
        }
    }

    private fun stubAnnotateVideoThrows(exception: Exception) {
        coEvery {
            videoVisualizationService.annotateVideo(
                videoPath = any(),
                conf = any(),
                imgSize = any(),
                maxDet = any(),
                detectEvery = any(),
                classes = any(),
                lineWidth = any(),
                showLabels = any(),
                showConf = any(),
                model = any(),
                onProgress = any(),
                onJobSubmitted = any(),
            )
        } throws exception
    }

    private fun assertAnnotateCalledWith(
        videoPath: Path,
        classes: String?,
        model: String,
    ) {
        val call = lastAnnotateCall ?: error("annotateVideo was never called")
        assertEquals(videoPath, call.videoPath, "videoPath mismatch")
        assertEquals(classes, call.classes, "classes mismatch")
        assertEquals(model, call.model, "model mismatch")
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
            errorMessage = null,
        )

    private fun recordingWithTimestamp(
        id: UUID = recordingId,
        camId: String? = "front",
        recordTimestamp: Instant? = this.recordTimestamp,
    ) = RecordingEntity(
        id = id,
        creationTimestamp = null,
        filePath = null,
        fileCreationTimestamp = null,
        camId = camId,
        recordDate = null,
        recordTime = null,
        recordTimestamp = recordTimestamp,
        startProcessingTimestamp = null,
        processTimestamp = null,
        processAttempts = null,
        detectionsCount = null,
        analyzeTime = null,
        analyzedFramesCount = null,
        errorMessage = null,
    )

    private fun stubExportByRecordingIdHappyPath(
        recording: RecordingEntity,
        duration: Duration = exportDuration,
    ): Path {
        val recordingFile = createTempFile("recording1.mp4")
        val mergedFile = createTempFile("merged.mp4")

        val expectedStart = recordTimestamp.minus(duration)
        val expectedEnd = recordTimestamp.plus(duration)

        coEvery { recordingRepository.findById(recordingId) } returns recording
        coEvery { recordingRepository.findByCamIdAndInstantRange("front", expectedStart, expectedEnd) } returns
            listOf(recording(recordingFile.toString()))
        coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile

        return mergedFile
    }

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
            stubAnnotateVideo(annotatedFile)

            val result =
                service.exportVideo(
                    startInstant = start,
                    endInstant = end,
                    camId = camId,
                    mode = ExportMode.ANNOTATED,
                )

            assertEquals(annotatedFile, result)
            assertAnnotateCalledWith(mergedFile, "person,car", "yolo26x.pt")
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
            stubAnnotateVideo(annotatedFile)

            val progress = mutableListOf<VideoExportProgress>()

            service.exportVideo(
                startInstant = start,
                endInstant = end,
                camId = camId,
                mode = ExportMode.ANNOTATED,
                onProgress = { progress.add(it) },
            )

            // Verify stage ordering: PREPARING → MERGING → ANNOTATING(0%)
            // Note: callback-driven progress (50%, 100%) cannot be tested via mock
            // due to Kotlin 2.x suspend lambda cast limitations with MockK spyk
            assertEquals(Stage.PREPARING, progress[0].stage)
            assertEquals(Stage.MERGING, progress[1].stage)
            assertEquals(Stage.ANNOTATING, progress[2].stage)
            assertEquals(0, progress[2].percent)
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
            stubAnnotateVideo(annotatedFile)

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
            stubAnnotateVideoThrows(RuntimeException("GPU out of memory"))

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

    @Test
    fun `annotated mode with empty allowedClasses passes null for classes`() =
        runTest {
            val emptyClassesService =
                VideoExportServiceImpl(
                    recordingRepository = recordingRepository,
                    videoMergeHelper = videoMergeHelper,
                    tempFileHelper = tempFileHelper,
                    videoVisualizationService = videoVisualizationService,
                    detectProperties = detectProperties,
                    detectionFilterProperties = DetectionFilterProperties(allowedClasses = emptyList()),
                )

            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")
            val annotatedFile = createTempFile("annotated.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
            stubAnnotateVideo(annotatedFile)

            val result =
                emptyClassesService.exportVideo(
                    startInstant = start,
                    endInstant = end,
                    camId = camId,
                    mode = ExportMode.ANNOTATED,
                )

            assertEquals(annotatedFile, result)
            assertAnnotateCalledWith(mergedFile, null, "yolo26x.pt")
        }

    @Test
    fun `annotated mode with blank allowedClasses entries filters them out`() =
        runTest {
            val blankEntriesService =
                VideoExportServiceImpl(
                    recordingRepository = recordingRepository,
                    videoMergeHelper = videoMergeHelper,
                    tempFileHelper = tempFileHelper,
                    videoVisualizationService = videoVisualizationService,
                    detectProperties = detectProperties,
                    detectionFilterProperties = DetectionFilterProperties(allowedClasses = listOf("person", "", "  ", "car")),
                )

            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")
            val annotatedFile = createTempFile("annotated.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
            stubAnnotateVideo(annotatedFile)

            val result =
                blankEntriesService.exportVideo(
                    startInstant = start,
                    endInstant = end,
                    camId = camId,
                    mode = ExportMode.ANNOTATED,
                )

            assertEquals(annotatedFile, result)
            assertAnnotateCalledWith(mergedFile, "person,car", "yolo26x.pt")
        }

    @Test
    fun `export original does not call VideoVisualizationService`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile

            service.exportVideo(
                startInstant = start,
                endInstant = end,
                camId = camId,
                mode = ExportMode.ORIGINAL,
            )

            coVerify(exactly = 0) {
                videoVisualizationService.annotateVideo(
                    videoPath = any(),
                    conf = any(),
                    imgSize = any(),
                    maxDet = any(),
                    detectEvery = any(),
                    classes = any(),
                    lineWidth = any(),
                    showLabels = any(),
                    showConf = any(),
                    model = any(),
                    onProgress = any(),
                    onJobSubmitted = any(),
                )
            }
        }

    @Test
    fun `annotated mode cleanup on timeout exception`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
            stubAnnotateVideoThrows(DetectTimeoutException("Detection timed out after 300s"))

            val exception =
                assertThrows<DetectTimeoutException> {
                    service.exportVideo(
                        startInstant = start,
                        endInstant = end,
                        camId = camId,
                        mode = ExportMode.ANNOTATED,
                    )
                }

            assertTrue(exception.message!!.contains("timed out"))
            coVerify(atLeast = 1) { tempFileHelper.deleteIfExists(mergedFile) }
        }

    // --- exportByRecordingId tests ---

    @Test
    fun `exportByRecordingId calls exportVideo with correct range and no annotation`() =
        runTest {
            val recording = recordingWithTimestamp()
            val mergedFile = stubExportByRecordingIdHappyPath(recording)

            val expectedStart = recordTimestamp.minus(exportDuration)
            val expectedEnd = recordTimestamp.plus(exportDuration)

            val result =
                service.exportByRecordingId(
                    recordingId = recordingId,
                    duration = exportDuration,
                )

            assertEquals(mergedFile, result)
            coVerify { recordingRepository.findById(recordingId) }
            coVerify {
                recordingRepository.findByCamIdAndInstantRange("front", expectedStart, expectedEnd)
            }
            coVerify(exactly = 0) {
                videoVisualizationService.annotateVideo(
                    videoPath = any(),
                    conf = any(),
                    imgSize = any(),
                    maxDet = any(),
                    detectEvery = any(),
                    classes = any(),
                    lineWidth = any(),
                    showLabels = any(),
                    showConf = any(),
                    model = any(),
                    onProgress = any(),
                    onJobSubmitted = any(),
                )
            }
        }

    @Test
    fun `exportByRecordingId throws IllegalArgumentException when recording not found`() =
        runTest {
            coEvery { recordingRepository.findById(recordingId) } returns null

            val exception =
                assertThrows<IllegalArgumentException> {
                    service.exportByRecordingId(recordingId = recordingId, duration = exportDuration)
                }

            assertTrue(exception.message!!.contains("Recording not found"))
            assertTrue(exception.message!!.contains(recordingId.toString()))
        }

    @Test
    fun `exportByRecordingId throws IllegalStateException when camId is null`() =
        runTest {
            val recording = recordingWithTimestamp(camId = null)
            coEvery { recordingRepository.findById(recordingId) } returns recording

            val exception =
                assertThrows<IllegalStateException> {
                    service.exportByRecordingId(recordingId = recordingId, duration = exportDuration)
                }

            assertTrue(exception.message!!.contains("has no camId"))
            assertTrue(exception.message!!.contains(recordingId.toString()))
        }

    @Test
    fun `exportByRecordingId throws IllegalStateException when recordTimestamp is null`() =
        runTest {
            val recording = recordingWithTimestamp(recordTimestamp = null)
            coEvery { recordingRepository.findById(recordingId) } returns recording

            val exception =
                assertThrows<IllegalStateException> {
                    service.exportByRecordingId(recordingId = recordingId, duration = exportDuration)
                }

            assertTrue(exception.message!!.contains("has no recordTimestamp"))
            assertTrue(exception.message!!.contains(recordingId.toString()))
        }

    @Test
    fun `exportByRecordingId throws IllegalArgumentException when duration is negative`() =
        runTest {
            val exception =
                assertThrows<IllegalArgumentException> {
                    service.exportByRecordingId(
                        recordingId = recordingId,
                        duration = Duration.ofMinutes(-1),
                    )
                }

            assertTrue(exception.message!!.contains("duration must be positive"))
        }

    @Test
    fun `exportByRecordingId throws IllegalArgumentException when duration is zero`() =
        runTest {
            val exception =
                assertThrows<IllegalArgumentException> {
                    service.exportByRecordingId(
                        recordingId = recordingId,
                        duration = Duration.ZERO,
                    )
                }

            assertTrue(exception.message!!.contains("duration must be positive"))
        }

    @Test
    fun `exportByRecordingId computes correct time range with custom duration`() =
        runTest {
            val customDuration = Duration.ofMinutes(5)
            val recording = recordingWithTimestamp()
            val mergedFile = stubExportByRecordingIdHappyPath(recording, duration = customDuration)

            val expectedStart = recordTimestamp.minus(customDuration)
            val expectedEnd = recordTimestamp.plus(customDuration)

            val result =
                service.exportByRecordingId(
                    recordingId = recordingId,
                    duration = customDuration,
                )

            assertEquals(mergedFile, result)
            coVerify {
                recordingRepository.findByCamIdAndInstantRange("front", expectedStart, expectedEnd)
            }
        }

    @Test
    fun `exportByRecordingId propagates progress from exportVideo`() =
        runTest {
            val recording = recordingWithTimestamp()
            stubExportByRecordingIdHappyPath(recording)

            val progress = mutableListOf<VideoExportProgress>()

            service.exportByRecordingId(
                recordingId = recordingId,
                duration = exportDuration,
                onProgress = { progress.add(it) },
            )

            assertTrue(progress.size >= 2)
            assertEquals(Stage.PREPARING, progress[0].stage)
            assertEquals(Stage.MERGING, progress[1].stage)
        }

    @Test
    fun `exportByRecordingId with ANNOTATED mode calls exportVideo with ANNOTATED`() =
        runTest {
            val recording = recordingWithTimestamp()
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged.mp4")
            val annotatedFile = createTempFile("annotated.mp4")

            val expectedStart = recordTimestamp.minus(exportDuration)
            val expectedEnd = recordTimestamp.plus(exportDuration)

            coEvery { recordingRepository.findById(recordingId) } returns recording
            coEvery { recordingRepository.findByCamIdAndInstantRange("front", expectedStart, expectedEnd) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
            stubAnnotateVideo(annotatedFile)

            val result =
                service.exportByRecordingId(
                    recordingId = recordingId,
                    duration = exportDuration,
                    mode = ExportMode.ANNOTATED,
                )

            assertEquals(annotatedFile, result)
            assertAnnotateCalledWith(mergedFile, "person,car", "yolo26x.pt")
        }

    // --- end exportByRecordingId tests ---

    @Test
    fun `export original with compress still too large throws and cleans up`() =
        runTest {
            val recordingFile = createTempFile("recording1.mp4")
            val mergedFile = createTempFile("merged-large.mp4")
            val compressedFile = createTempFile("compressed-large.mp4")

            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, start, end) } returns
                listOf(recording(recordingFile.toString()))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
            coEvery { videoMergeHelper.compressVideo(mergedFile) } returns compressedFile
            coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
            coEvery { tempFileHelper.deleteIfExists(compressedFile) } returns true

            mockkStatic(Files::class)
            try {
                every { Files.exists(any<Path>()) } returns true
                every { Files.size(mergedFile) } returns VideoMergeHelper.COMPRESS_THRESHOLD_BYTES + 1
                every { Files.size(compressedFile) } returns VideoMergeHelper.MAX_FILE_SIZE_BYTES + 1

                val exception =
                    assertThrows<IllegalStateException> {
                        service.exportVideo(
                            startInstant = start,
                            endInstant = end,
                            camId = camId,
                            mode = ExportMode.ORIGINAL,
                        )
                    }

                assertTrue(exception.message!!.contains("too large"))
                coVerify { tempFileHelper.deleteIfExists(mergedFile) }
                coVerify { tempFileHelper.deleteIfExists(compressedFile) }
            } finally {
                unmockkStatic(Files::class)
            }
        }

    @Test
    fun `exportVideo plumbs onJobSubmitted through to annotateVideo for ANNOTATED mode`() {
        runBlocking {
            val camId = "cam1"
            val rangeStart = Instant.parse("2026-02-16T12:00:00Z")
            val rangeEnd = Instant.parse("2026-02-16T12:05:00Z")
            val recFile = tempDir.resolve("rec-${UUID.randomUUID()}.mp4")
            Files.write(recFile, byteArrayOf(1, 2, 3, 4, 5))
            val recordingEntity =
                RecordingEntity(
                    id = UUID.randomUUID(),
                    creationTimestamp = null,
                    filePath = recFile.toString(),
                    fileCreationTimestamp = null,
                    camId = camId,
                    recordDate = null,
                    recordTime = null,
                    recordTimestamp = rangeStart,
                    startProcessingTimestamp = null,
                    processTimestamp = null,
                    processAttempts = null,
                    detectionsCount = null,
                    analyzeTime = null,
                    analyzedFramesCount = null,
                    errorMessage = null,
                )
            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, rangeStart, rangeEnd) } returns listOf(recordingEntity)
            val mergedPath = tempDir.resolve("merged-${UUID.randomUUID()}.mp4")
            Files.write(mergedPath, ByteArray(100))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedPath
            coEvery { tempFileHelper.deleteIfExists(mergedPath) } returns true
            val annotatedPath = tempDir.resolve("ann-${UUID.randomUUID()}.mp4")
            Files.write(annotatedPath, ByteArray(50))
            val capturedCallback = slot<suspend (ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob) -> Unit>()
            coEvery {
                videoVisualizationService.annotateVideo(
                    videoPath = any(),
                    classes = any(),
                    model = any(),
                    onProgress = any(),
                    onJobSubmitted = capture(capturedCallback),
                )
            } returns annotatedPath

            var received: ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob? = null
            val result =
                service.exportVideo(
                    startInstant = rangeStart,
                    endInstant = rangeEnd,
                    camId = camId,
                    mode = ExportMode.ANNOTATED,
                    onProgress = {},
                    onJobSubmitted = { received = it },
                )

            val testCancellable =
                ru.zinin.frigate.analyzer.telegram.service.model
                    .CancellableJob { /* noop */ }
            capturedCallback.captured.invoke(testCancellable)

            assertEquals(annotatedPath, result)
            assertEquals(testCancellable, received)
        }
    }

    @Test
    fun `exportVideo does not call annotateVideo for ORIGINAL mode`() {
        runBlocking {
            val camId = "cam1"
            val rangeStart = Instant.parse("2026-02-16T12:00:00Z")
            val rangeEnd = Instant.parse("2026-02-16T12:05:00Z")
            val recFile = tempDir.resolve("rec-${UUID.randomUUID()}.mp4")
            Files.write(recFile, byteArrayOf(1, 2, 3))
            val recordingEntity =
                RecordingEntity(
                    id = UUID.randomUUID(),
                    creationTimestamp = null,
                    filePath = recFile.toString(),
                    fileCreationTimestamp = null,
                    camId = camId,
                    recordDate = null,
                    recordTime = null,
                    recordTimestamp = rangeStart,
                    startProcessingTimestamp = null,
                    processTimestamp = null,
                    processAttempts = null,
                    detectionsCount = null,
                    analyzeTime = null,
                    analyzedFramesCount = null,
                    errorMessage = null,
                )
            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, rangeStart, rangeEnd) } returns listOf(recordingEntity)
            val mergedPath = tempDir.resolve("merged-${UUID.randomUUID()}.mp4")
            Files.write(mergedPath, ByteArray(100))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedPath

            var invoked = false
            service.exportVideo(
                startInstant = rangeStart,
                endInstant = rangeEnd,
                camId = camId,
                mode = ExportMode.ORIGINAL,
                onProgress = {},
                onJobSubmitted = { invoked = true },
            )

            assertEquals(false, invoked)
            coVerify(exactly = 0) {
                videoVisualizationService.annotateVideo(
                    videoPath = any(),
                    conf = any(),
                    imgSize = any(),
                    maxDet = any(),
                    detectEvery = any(),
                    classes = any(),
                    lineWidth = any(),
                    showLabels = any(),
                    showConf = any(),
                    model = any(),
                    onProgress = any(),
                    onJobSubmitted = any(),
                )
            }
        }
    }

    @Test
    fun `exportByRecordingId plumbs onJobSubmitted through exportVideo for ANNOTATED mode`() {
        runBlocking {
            // Guards iter-4 codex TEST-3: QuickExportHandler calls videoExportService.exportByRecordingId(...),
            // NOT exportVideo(...). The happy-path test above (`exportVideo plumbs onJobSubmitted ...`) only
            // covers the /export entry point; this one ensures the QuickExport entry point propagates the
            // callback down to annotateVideo as well. Without this test, a regression that silently drops
            // `onJobSubmitted` inside exportByRecordingId() would leave QuickExport users unable to cancel
            // vision-server jobs (visible symptom: "Cancelled" in UI, but the job keeps running on GPU).
            val camId = "cam1"
            val recordingId = UUID.randomUUID()
            val recordTimestamp = Instant.parse("2026-02-16T12:03:00Z")
            val duration = Duration.ofMinutes(1)
            val rangeStart = recordTimestamp.minus(duration)
            val rangeEnd = recordTimestamp.plus(duration)
            val recFile = tempDir.resolve("rec-${UUID.randomUUID()}.mp4")
            Files.write(recFile, byteArrayOf(1, 2, 3, 4, 5))
            val recordingEntity =
                RecordingEntity(
                    id = recordingId,
                    creationTimestamp = null,
                    filePath = recFile.toString(),
                    fileCreationTimestamp = null,
                    camId = camId,
                    recordDate = null,
                    recordTime = null,
                    recordTimestamp = recordTimestamp,
                    startProcessingTimestamp = null,
                    processTimestamp = null,
                    processAttempts = null,
                    detectionsCount = null,
                    analyzeTime = null,
                    analyzedFramesCount = null,
                    errorMessage = null,
                )
            coEvery { recordingRepository.findById(recordingId) } returns recordingEntity
            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, rangeStart, rangeEnd) } returns listOf(recordingEntity)
            val mergedPath = tempDir.resolve("merged-${UUID.randomUUID()}.mp4")
            Files.write(mergedPath, ByteArray(100))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedPath
            coEvery { tempFileHelper.deleteIfExists(mergedPath) } returns true
            val annotatedPath = tempDir.resolve("ann-${UUID.randomUUID()}.mp4")
            Files.write(annotatedPath, ByteArray(50))
            val capturedCallback = slot<suspend (ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob) -> Unit>()
            coEvery {
                videoVisualizationService.annotateVideo(
                    videoPath = any(),
                    classes = any(),
                    model = any(),
                    onProgress = any(),
                    onJobSubmitted = capture(capturedCallback),
                )
            } returns annotatedPath

            var received: ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob? = null
            val result =
                service.exportByRecordingId(
                    recordingId = recordingId,
                    duration = duration,
                    mode = ExportMode.ANNOTATED,
                    onProgress = {},
                    onJobSubmitted = { received = it },
                )

            val testCancellable =
                ru.zinin.frigate.analyzer.telegram.service.model
                    .CancellableJob { /* noop */ }
            capturedCallback.captured.invoke(testCancellable)

            assertEquals(annotatedPath, result)
            assertEquals(testCancellable, received)
        }
    }

    @Test
    fun `exportByRecordingId does not invoke onJobSubmitted for ORIGINAL mode`() {
        runBlocking {
            // Negative companion to the above test — ORIGINAL flow must not call annotateVideo at all,
            // so the onJobSubmitted callback is never triggered. Protects against accidentally always
            // invoking the callback in exportByRecordingId regardless of mode.
            val camId = "cam1"
            val recordingId = UUID.randomUUID()
            val recordTimestamp = Instant.parse("2026-02-16T12:03:00Z")
            val duration = Duration.ofMinutes(1)
            val rangeStart = recordTimestamp.minus(duration)
            val rangeEnd = recordTimestamp.plus(duration)
            val recFile = tempDir.resolve("rec-${UUID.randomUUID()}.mp4")
            Files.write(recFile, byteArrayOf(1, 2, 3))
            val recordingEntity =
                RecordingEntity(
                    id = recordingId,
                    creationTimestamp = null,
                    filePath = recFile.toString(),
                    fileCreationTimestamp = null,
                    camId = camId,
                    recordDate = null,
                    recordTime = null,
                    recordTimestamp = recordTimestamp,
                    startProcessingTimestamp = null,
                    processTimestamp = null,
                    processAttempts = null,
                    detectionsCount = null,
                    analyzeTime = null,
                    analyzedFramesCount = null,
                    errorMessage = null,
                )
            coEvery { recordingRepository.findById(recordingId) } returns recordingEntity
            coEvery { recordingRepository.findByCamIdAndInstantRange(camId, rangeStart, rangeEnd) } returns listOf(recordingEntity)
            val mergedPath = tempDir.resolve("merged-${UUID.randomUUID()}.mp4")
            Files.write(mergedPath, ByteArray(100))
            coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedPath

            var invoked = false
            service.exportByRecordingId(
                recordingId = recordingId,
                duration = duration,
                mode = ExportMode.ORIGINAL,
                onProgress = {},
                onJobSubmitted = { invoked = true },
            )

            assertEquals(false, invoked)
            coVerify(exactly = 0) {
                videoVisualizationService.annotateVideo(
                    videoPath = any(),
                    conf = any(),
                    imgSize = any(),
                    maxDet = any(),
                    detectEvery = any(),
                    classes = any(),
                    lineWidth = any(),
                    showLabels = any(),
                    showConf = any(),
                    model = any(),
                    onProgress = any(),
                    onJobSubmitted = any(),
                )
            }
        }
    }
}
