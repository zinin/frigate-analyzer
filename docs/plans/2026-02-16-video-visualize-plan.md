# Video Visualize — DetectService Extension Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extend DetectService with async job-based `/detect/video/visualize` API and create VideoVisualizationService orchestrator.

**Architecture:** Three new HTTP methods in DetectService (submit, pollStatus, download) that take `AcquiredServer` as parameter (no slot management). VideoVisualizationService orchestrates the full lifecycle (acquire slot → submit → poll → download → release slot) with progress callback. Input video as `Path` (streaming upload from file). Download streams to temp file (returns `Path`). New RequestType.VIDEO_VISUALIZE in load balancer with dedicated capacity slots held until job completion. JobStatus enum for type-safe status handling. 404 during polling = terminal error. Orphan job logging only on abnormal termination.

**Tech Stack:** Kotlin, Spring WebFlux WebClient, Coroutines, MockWebServer (tests)

**Design doc:** `docs/plans/2026-02-16-video-visualize-design.md`

---

### Task 1: Response Models & Exception

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/JobCreatedResponse.kt`
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/JobStatus.kt`
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/JobStatusResponse.kt`
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/exception/VideoAnnotationFailedException.kt`

**Step 1: Create JobCreatedResponse.kt**

```kotlin
package ru.zinin.frigate.analyzer.model.response

data class JobCreatedResponse(
    val jobId: String,
    val status: String,
    val message: String,
)
```

**Step 2: Create JobStatus.kt**

```kotlin
package ru.zinin.frigate.analyzer.model.response

import tools.jackson.annotation.JsonProperty

enum class JobStatus {
    @JsonProperty("queued") QUEUED,
    @JsonProperty("processing") PROCESSING,
    @JsonProperty("completed") COMPLETED,
    @JsonProperty("failed") FAILED,
}
```

**Step 3: Create JobStatusResponse.kt**

```kotlin
package ru.zinin.frigate.analyzer.model.response

data class JobStatusResponse(
    val jobId: String,
    val status: JobStatus,
    val progress: Int,
    val createdAt: String,
    val completedAt: String?,
    val downloadUrl: String?,
    val error: String?,
    val stats: JobStats?,
)

data class JobStats(
    val totalFrames: Int,
    val detectedFrames: Int,
    val trackedFrames: Int,
    val totalDetections: Int,
    val processingTimeMs: Int,
)
```

**Step 4: Create VideoAnnotationFailedException.kt**

```kotlin
package ru.zinin.frigate.analyzer.model.exception

class VideoAnnotationFailedException(
    message: String?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

**Step 5: Commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/JobCreatedResponse.kt \
       modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/JobStatus.kt \
       modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/JobStatusResponse.kt \
       modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/exception/VideoAnnotationFailedException.kt
git commit -m "feat: add video visualize response models and exception"
```

---

### Task 2: Configuration — VideoVisualizeConfig

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectProperties.kt:29` — add field + new data class
- Modify: `modules/core/src/main/resources/application.yaml:69` — add config block
- Modify: `modules/core/src/test/resources/application.yaml:72` — add config block

**Step 1: Add VideoVisualizeConfig class and field to DetectProperties.kt**

Add at end of `DetectProperties` data class (before closing paren, line 29):
```kotlin
    @field:Valid
    val videoVisualize: VideoVisualizeConfig = VideoVisualizeConfig(),
```

Add new data class at end of file (after `VisualizeConfig`):
```kotlin
data class VideoVisualizeConfig(
    val timeout: Duration = Duration.ofMinutes(15),
    val pollInterval: Duration = Duration.ofSeconds(3),
    @field:Min(1)
    val maxDet: Int = 100,
    @field:Min(1)
    val detectEvery: Int? = null,
    @field:Min(1)
    val lineWidth: Int = 2,
    val showLabels: Boolean = true,
    val showConf: Boolean = true,
)
```

**Step 2: Add config to main application.yaml**

After the `visualize:` block (after line 69), add:
```yaml
    video-visualize:
      timeout: ${DETECT_VIDEO_VISUALIZE_TIMEOUT:15m}
      poll-interval: ${DETECT_VIDEO_VISUALIZE_POLL_INTERVAL:3s}
      max-det: ${DETECT_VIDEO_VISUALIZE_MAX_DET:100}
      detect-every: ${DETECT_VIDEO_VISUALIZE_DETECT_EVERY:}
      line-width: ${DETECT_VIDEO_VISUALIZE_LINE_WIDTH:2}
      show-labels: ${DETECT_VIDEO_VISUALIZE_SHOW_LABELS:true}
      show-conf: ${DETECT_VIDEO_VISUALIZE_SHOW_CONF:true}
```

**Step 3: Add config to test application.yaml**

Add same block in the `detect:` section of test config.

**Step 4: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectProperties.kt \
       modules/core/src/main/resources/application.yaml \
       modules/core/src/test/resources/application.yaml
git commit -m "feat: add VideoVisualizeConfig to DetectProperties"
```

---

### Task 3: Load Balancer — VIDEO_VISUALIZE Support

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/RequestType.kt:6` — add enum value
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerState.kt` — add counter + when branches
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectServerProperties.kt:36` — add field
- Modify: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatisticsResponse.kt:28` — add field
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/DetectServerLoadBalancer.kt` — update init, statistics, unavailable message

**Step 1: Add VIDEO_VISUALIZE to RequestType.kt**

```kotlin
enum class RequestType {
    FRAME,
    FRAME_EXTRACTION,
    VISUALIZE,
    VIDEO_VISUALIZE,
}
```

**Step 2: Update ServerState.kt**

Add new counter to data class (after line 15):
```kotlin
    val processingVideoVisualizeRequestsCount: AtomicInteger = AtomicInteger(0),
```

Add `RequestType.VIDEO_VISUALIZE` branches to all three `when` blocks:

In `getCurrentCount`:
```kotlin
            RequestType.VIDEO_VISUALIZE -> processingVideoVisualizeRequestsCount.get()
```

In `getMaxCount`:
```kotlin
            RequestType.VIDEO_VISUALIZE -> properties.videoVisualizeRequests.simultaneousCount
```

In `getCounter` extension:
```kotlin
        RequestType.VIDEO_VISUALIZE -> processingVideoVisualizeRequestsCount
```

In `getRequestConfig` extension:
```kotlin
        RequestType.VIDEO_VISUALIZE -> properties.videoVisualizeRequests
```

**Step 3: Add videoVisualizeRequests to DetectServerProperties.kt**

Add after `visualizeRequests` (after line 36, before closing paren):
```kotlin
    /**
     * Конфигурация для запросов видео-визуализации
     */
    @field:NotNull
    @field:Valid
    val videoVisualizeRequests: RequestConfig,
```

**Step 4: Add videoVisualizeRequests to DetectServerStatistics**

In `StatisticsResponse.kt`, add to `DetectServerStatistics` (after line 28):
```kotlin
    val videoVisualizeRequests: ServerLoad,
```

**Step 5: Update DetectServerLoadBalancer.kt**

In `init()` (line 37-42), add to the log message:
```kotlin
                    "videoVisualizeRequests(count=${props.videoVisualizeRequests.simultaneousCount}, " +
                    "priority=${props.videoVisualizeRequests.priority})"
```

In `getAllServersStatistics()` (line 80-101), add after `visualizeRequests`:
```kotlin
                videoVisualizeRequests =
                    ServerLoad(
                        current = server.processingVideoVisualizeRequestsCount.get(),
                        maximum = server.properties.videoVisualizeRequests.simultaneousCount,
                    ),
```

In `buildUnavailableMessage()` (line 103-113), add to status string:
```kotlin
                    "videoVisualize=${server.processingVideoVisualizeRequestsCount.get()}/${server.properties.videoVisualizeRequests.simultaneousCount})"
```

**Step 6: Update test config and test setUp**

In `modules/core/src/test/resources/application.yaml`, add `video-visualize-requests` to the mock server config (after line 69):
```yaml
      video-visualize-requests:
        simultaneous-count: 1
        priority: 1
```

In `DetectServiceTest.kt`, update `serverProps` constructor (around line 54-61) to add:
```kotlin
                videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
```

Also update the `secondaryProps` in multi-server tests (around lines 232-240, 262-268) to add:
```kotlin
                videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 10),
```

**Step 7: Run existing tests to verify nothing broke**

Run: `./gradlew :core:test`
Expected: All existing tests PASS

**Step 8: Update docker template**

In `docker/deploy/application-docker.yaml.example`, add `video-visualize-requests` to each detect server config:
```yaml
      video-visualize-requests:
        simultaneous-count: 1
        priority: 1
```

**Step 9: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/RequestType.kt \
       modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerState.kt \
       modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectServerProperties.kt \
       modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatisticsResponse.kt \
       modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/DetectServerLoadBalancer.kt \
       modules/core/src/test/resources/application.yaml \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt \
       docker/deploy/application-docker.yaml.example
git commit -m "feat: add VIDEO_VISUALIZE to load balancer"
```

---

### Task 4: Test Dispatcher — Video Endpoints

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/DetectServiceDispatcher.kt` — add 3 endpoints to both dispatchers

**Step 1: Add video endpoints to DetectServiceDispatcher**

Add these cases to the `when (path)` block in `DetectServiceDispatcher.dispatch()` (before `else` branch, line 47):

```kotlin
            "/detect/video/visualize" -> {
                if (method != "POST") {
                    respondJson(405, errorJson("Method Not Allowed"))
                } else {
                    respondJson(202, jobCreatedResponseJson())
                }
            }

            else -> {
                if (path.matches(Regex("/jobs/[^/]+/download"))) {
                    respondBinary(200, "video/mp4", fakeVideoBytes())
                } else if (path.matches(Regex("/jobs/[^/]+"))) {
                    respondJson(200, jobStatusCompletedJson())
                } else {
                    respondJson(404, errorJson("Not Found"))
                }
            }
```

Note: Replace the existing `else` branch entirely with the new one above.

Add JSON helper methods to `DetectServiceDispatcher`:

```kotlin
    private fun jobCreatedResponseJson(): String =
        """
        {
          "job_id": "test-job-123",
          "status": "queued",
          "message": "Video annotation job created"
        }
        """.trimIndent()

    private fun jobStatusCompletedJson(): String =
        """
        {
          "job_id": "test-job-123",
          "status": "completed",
          "progress": 100,
          "created_at": "2026-02-16T12:00:00Z",
          "completed_at": "2026-02-16T12:05:00Z",
          "download_url": "/jobs/test-job-123/download",
          "error": null,
          "stats": {
            "total_frames": 300,
            "detected_frames": 50,
            "tracked_frames": 250,
            "total_detections": 120,
            "processing_time_ms": 15000
          }
        }
        """.trimIndent()

    private fun fakeVideoBytes(): ByteArray =
        byteArrayOf(
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1C.toByte(),
            0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte(),
        )
```

**Step 2: Add same endpoints to ConfigurableDetectServiceDispatcher**

Apply the same changes to the `when (path)` block and add the same helper methods. The failure logic at the top of `dispatch()` already handles returning errors for initial failures before routing.

**Step 3: Run existing tests**

Run: `./gradlew :core:test`
Expected: All existing tests PASS (new endpoints are additive)

**Step 4: Commit**

```bash
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/DetectServiceDispatcher.kt
git commit -m "test: add video visualize endpoints to mock dispatcher"
```

---

### Task 5: DetectService.submitVideoVisualize

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt` — add method
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt` — add test

**IMPORTANT (review decision C1):** submitVideoVisualize принимает `AcquiredServer` как параметр. НЕ делает acquire/release внутри. Caller (VideoVisualizationService) управляет slot lifecycle.

**Step 1: Write failing test**

Add to `DetectServiceTest.kt`:

```kotlin
    @Test
    fun `submitVideoVisualize returns job response for given server`() =
        runBlocking {
            val acquired = loadBalancer.acquireServer(RequestType.VIDEO_VISUALIZE)

            val testVideoPath = Files.createTempFile("test-video-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))

            val jobResponse = detectService.submitVideoVisualize(
                acquired = acquired,
                videoPath = testVideoPath,
            )

            assertEquals("test-job-123", jobResponse.jobId)
            assertEquals("queued", jobResponse.status)

            val request = mockWebServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/detect/video/visualize", request.url.encodedPath)
            assertTrue(request.url.query!!.contains("conf=0.6"))
            assertTrue(request.url.query!!.contains("imgsz=2016"))

            // Cleanup: release manually
            loadBalancer.releaseServer(acquired.id, RequestType.VIDEO_VISUALIZE)
        }
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests '*DetectServiceTest.submitVideoVisualize*'`
Expected: FAIL — method does not exist

**Step 3: Implement submitVideoVisualize in DetectService.kt**

Add imports:
```kotlin
import ru.zinin.frigate.analyzer.core.loadbalancer.AcquiredServer
import ru.zinin.frigate.analyzer.model.response.JobCreatedResponse
```

Add method:

```kotlin
    /**
     * Отправляет видео на аннотацию на конкретный сервер.
     * НЕ управляет слотами — caller отвечает за acquire/release.
     *
     * @throws Exception при ошибке от сервера
     */
    suspend fun submitVideoVisualize(
        acquired: AcquiredServer,
        videoPath: Path,
        conf: Double = detectProperties.defaultConfidence,
        imgSize: Int = detectProperties.defaultImgSize,
        maxDet: Int = detectProperties.videoVisualize.maxDet,
        detectEvery: Int? = detectProperties.videoVisualize.detectEvery,
        classes: String? = null,
        lineWidth: Int = detectProperties.videoVisualize.lineWidth,
        showLabels: Boolean = detectProperties.videoVisualize.showLabels,
        showConf: Boolean = detectProperties.videoVisualize.showConf,
        model: String = detectProperties.defaultModel,
    ): JobCreatedResponse {
        val filename = videoPath.fileName.toString()
        val fileResource = FileSystemResource(videoPath)
        val multipartData =
            MultipartBodyBuilder()
                .apply {
                    part("file", fileResource)
                        .contentType(MediaType.parseMediaType("video/mp4"))
                        .filename(filename)
                }.build()

        return webClient
            .post()
            .uri { uriBuilder ->
                uriBuilder
                    .scheme(acquired.schema)
                    .host(acquired.host)
                    .port(acquired.port)
                    .path("/detect/video/visualize")
                    .queryParam("conf", conf)
                    .queryParam("imgsz", imgSize)
                    .queryParam("max_det", maxDet)
                    .apply {
                        detectEvery?.let { queryParam("detect_every", it) }
                        classes?.let { queryParam("classes", it) }
                    }
                    .queryParam("line_width", lineWidth)
                    .queryParam("show_labels", showLabels)
                    .queryParam("show_conf", showConf)
                    .queryParam("model", model)
                    .build()
            }.body(BodyInserters.fromMultipartData(multipartData))
            .retrieve()
            .bodyToMono<JobCreatedResponse>()
            .awaitSingle()
    }
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests '*DetectServiceTest.submitVideoVisualize*'`
Expected: PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt
git commit -m "feat: add DetectService.submitVideoVisualize"
```

---

### Task 6: DetectService.getJobStatus

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt` — add method
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt` — add test

**Step 1: Write failing test**

```kotlin
    @Test
    fun `getJobStatus returns job status from specific server`() =
        runBlocking {
            val acquired = loadBalancer.acquireServer(RequestType.VIDEO_VISUALIZE)

            val status = detectService.getJobStatus(acquired, "test-job-123")

            assertEquals("test-job-123", status.jobId)
            assertEquals(JobStatus.COMPLETED, status.status)
            assertEquals(100, status.progress)
            assertNotNull(status.stats)
            assertEquals(300, status.stats!!.totalFrames)

            // Cleanup
            loadBalancer.releaseServer(acquired.id, RequestType.VIDEO_VISUALIZE)
        }
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests '*DetectServiceTest.getJobStatus*'`
Expected: FAIL — method does not exist

**Step 3: Implement getJobStatus in DetectService.kt**

Add import:
```kotlin
import ru.zinin.frigate.analyzer.model.response.JobStatusResponse
```

Add method:
```kotlin
    /**
     * Опрашивает статус job на конкретном сервере.
     * Не использует load balancer — обращается напрямую к серверу, на котором запущен job.
     */
    suspend fun getJobStatus(
        acquired: AcquiredServer,
        jobId: String,
    ): JobStatusResponse =
        webClient
            .get()
            .uri { uriBuilder ->
                uriBuilder
                    .scheme(acquired.schema)
                    .host(acquired.host)
                    .port(acquired.port)
                    .path("/jobs/{jobId}")
                    .build(jobId)
            }.retrieve()
            .bodyToMono<JobStatusResponse>()
            .awaitSingle()
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests '*DetectServiceTest.getJobStatus*'`
Expected: PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt
git commit -m "feat: add DetectService.getJobStatus"
```

---

### Task 7: DetectService.downloadJobResult

**IMPORTANT (review decision C2):** downloadJobResult использует streaming запись во временный файл и возвращает `Path` вместо `ByteArray`. Это предотвращает OOM на больших видео.

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt` — add method
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt` — add test

**Step 1: Write failing test**

```kotlin
    @Test
    fun `downloadJobResult streams video to temp file`() =
        runBlocking {
            val acquired = loadBalancer.acquireServer(RequestType.VIDEO_VISUALIZE)

            val videoPath = detectService.downloadJobResult(acquired, "test-job-123")

            // Verify file exists and has content
            assertTrue(Files.exists(videoPath))
            val videoBytes = Files.readAllBytes(videoPath)
            assertTrue(videoBytes.isNotEmpty())
            // fakeVideoBytes from dispatcher: ftyp MP4 header
            assertEquals(0x66.toByte(), videoBytes[4]) // 'f'
            assertEquals(0x74.toByte(), videoBytes[5]) // 't'
            assertEquals(0x79.toByte(), videoBytes[6]) // 'y'
            assertEquals(0x70.toByte(), videoBytes[7]) // 'p'

            // Cleanup
            Files.deleteIfExists(videoPath)
            loadBalancer.releaseServer(acquired.id, RequestType.VIDEO_VISUALIZE)
        }
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests '*DetectServiceTest.downloadJobResult*'`
Expected: FAIL — method does not exist

**Step 3: Implement downloadJobResult in DetectService.kt**

Uses `DataBuffer` streaming to write directly to a temp file instead of loading into memory.

```kotlin
    /**
     * Скачивает результат аннотированного видео с конкретного сервера.
     * Использует streaming запись во временный файл для избежания OOM.
     * Не использует load balancer — обращается напрямую к серверу, на котором завершился job.
     *
     * @return Path к временному файлу с видео. Caller отвечает за удаление.
     */
    suspend fun downloadJobResult(
        acquired: AcquiredServer,
        jobId: String,
    ): Path {
        val tempFile = Files.createTempFile("video-annotated-", ".mp4")

        try {
            Files.newOutputStream(tempFile).use { outputStream ->
                webClient
                    .get()
                    .uri { uriBuilder ->
                        uriBuilder
                            .scheme(acquired.schema)
                            .host(acquired.host)
                            .port(acquired.port)
                            .path("/jobs/{jobId}/download")
                            .build(jobId)
                    }.retrieve()
                    .bodyToFlux<DataBuffer>()
                    .asFlow()
                    .collect { dataBuffer ->
                        try {
                            outputStream.write(dataBuffer.asInputStream().readAllBytes())
                        } finally {
                            DataBufferUtils.release(dataBuffer)
                        }
                    }
            }
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile)
            throw e
        }

        return tempFile
    }
```

Note: Check existing codebase for DataBuffer/streaming patterns and adapt accordingly.

**Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests '*DetectServiceTest.downloadJobResult*'`
Expected: PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt
git commit -m "feat: add DetectService.downloadJobResult with streaming"
```

---

### Task 8: VideoVisualizationService — Happy Path

**IMPORTANT (review decisions):**
- **C1:** VideoVisualizationService manages slot lifecycle (acquireServer/releaseServer). DetectService methods just do HTTP.
- **C2:** annotateVideo returns `Path` (temp file), not `ByteArray`.
- **C5:** Slot management pseudocode: acquire in retry loop, release on failure, keep on success.
- **C7:** 404 during polling = terminal error (job lost after server restart).
- **C8:** Orphan job logging only when !completed (use flag).
- **N1:** Log orphan jobs on timeout/cancel.
- **N5:** Use `JobStatus` enum in when blocks.
- **N12:** Wrap onProgress in try-catch (exception in callback must not kill job).
- **N18:** Input video as `Path`, not `ByteArray` (streaming upload from file).
- **C3:** Retry logic by analogy with existing DetectService methods.

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationService.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt`

**Step 1: Write failing test — happy path**

Create `VideoVisualizationServiceTest.kt` with setUp (MockWebServer, DetectServiceDispatcher, registry, loadBalancer, detectProperties, service). See existing `DetectServiceTest` for patterns.

```kotlin
    @Test
    fun `annotateVideo returns video file path and releases server`() =
        runBlocking {
            val progressUpdates = mutableListOf<JobStatusResponse>()

            val testVideoPath = Files.createTempFile("test-input-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))

            val resultPath = service.annotateVideo(
                videoPath = testVideoPath,
                onProgress = { progressUpdates.add(it) },
            )

            // Verify result is a valid file
            assertTrue(Files.exists(resultPath))
            assertTrue(Files.size(resultPath) > 0)

            // Verify progress callback was called
            assertTrue(progressUpdates.isNotEmpty())
            assertEquals(JobStatus.COMPLETED, progressUpdates.last().status)

            // Server should be released
            assertEquals(0, registry.getServer("test")!!.processingVideoVisualizeRequestsCount.get())

            // Cleanup temp file
            Files.deleteIfExists(resultPath)
        }
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests '*VideoVisualizationServiceTest*'`
Expected: FAIL — class does not exist

**Step 3: Implement VideoVisualizationService**

Create `VideoVisualizationService.kt`:

```kotlin
@Service
class VideoVisualizationService(
    private val detectService: DetectService,
    private val loadBalancer: DetectServerLoadBalancer,
    private val detectProperties: DetectProperties,
) {
    /**
     * Отправляет видео на аннотацию, опрашивает прогресс и скачивает результат.
     *
     * Три фазы:
     * 1. Submit — с retry между серверами при ошибках
     * 2. Poll — на фиксированном сервере до completed/failed
     * 3. Download — с того же сервера (streaming в temp file)
     *
     * Оркестратор управляет slot lifecycle (acquire/release).
     *
     * @param onProgress вызывается на каждом успешном poll
     * @return Path к временному файлу с аннотированным видео. Caller отвечает за удаление.
     */
    suspend fun annotateVideo(
        videoPath: Path,
        conf: Double = detectProperties.defaultConfidence,
        imgSize: Int = detectProperties.defaultImgSize,
        maxDet: Int = detectProperties.videoVisualize.maxDet,
        detectEvery: Int? = detectProperties.videoVisualize.detectEvery,
        classes: String? = null,
        lineWidth: Int = detectProperties.videoVisualize.lineWidth,
        showLabels: Boolean = detectProperties.videoVisualize.showLabels,
        showConf: Boolean = detectProperties.videoVisualize.showConf,
        model: String = detectProperties.defaultModel,
        onProgress: suspend (JobStatusResponse) -> Unit = {},
    ): Path {
        val timeoutMs = detectProperties.videoVisualize.timeout.toMillis()
        val pollIntervalMs = detectProperties.videoVisualize.pollInterval.toMillis()
        var acquired: AcquiredServer? = null
        var jobId: String? = null
        var completed = false

        try {
            val result = withTimeout(timeoutMs) {
                // Phase 1: Submit with retry (acquireServer + submit, retry on failure)
                val (server, job) = submitWithRetry(videoPath, conf, imgSize, maxDet,
                    detectEvery, classes, lineWidth, showLabels, showConf, model)
                acquired = server
                jobId = job.jobId

                logger.info { "Video annotation job ${job.jobId} submitted to server ${server.id}" }

                // Phase 2: Poll until completed/failed
                while (true) {
                    delay(pollIntervalMs)
                    val status = try {
                        detectService.getJobStatus(server, job.jobId)
                    } catch (e: CancellationException) { throw e }
                    catch (e: WebClientResponseException.NotFound) {
                        // 404 = job lost (server restarted) — terminal error
                        throw VideoAnnotationFailedException(
                            "Job ${job.jobId} not found on server ${server.id} (server may have restarted)", e)
                    }
                    catch (e: Exception) {
                        logger.warn { "Poll failed for job ${job.jobId}: ${e.message}" }
                        continue
                    }

                    try { onProgress(status) } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { logger.warn(e) { "onProgress callback failed for job ${job.jobId}" } }

                    when (status.status) {
                        JobStatus.COMPLETED -> {
                            logger.info { "Job ${job.jobId} completed. Stats: ${status.stats}" }
                            break
                        }
                        JobStatus.FAILED -> throw VideoAnnotationFailedException(
                            "Video annotation job ${job.jobId} failed: ${status.error}")
                        else -> {} // QUEUED, PROCESSING → continue polling
                    }
                }

                // Phase 3: Download (streaming to temp file)
                detectService.downloadJobResult(server, job.jobId)
            }
            completed = true
            return result
        } catch (e: TimeoutCancellationException) {
            logger.error { "Video annotation timed out after ${timeoutMs}ms (videoPath=$videoPath)" }
            throw DetectTimeoutException("Video annotation timed out after ${timeoutMs}ms", e)
        } finally {
            acquired?.let { server ->
                loadBalancer.releaseServer(server.id, RequestType.VIDEO_VISUALIZE)
                if (!completed && jobId != null) {
                    logger.warn { "Orphan job $jobId may still be running on server ${server.id}" }
                }
            }
        }
    }

    private suspend fun submitWithRetry(...): Pair<AcquiredServer, JobCreatedResponse> {
        // Retry logic by analogy with existing DetectService methods.
        // In a loop:
        //   val server = loadBalancer.acquireServer(VIDEO_VISUALIZE)
        //   try { submit → return Pair(server, job) }
        //   catch (e: CancellationException) { releaseServer; rethrow }
        //   catch (e: Exception) { releaseServer; delay; retry }
        // On DetectServerUnavailableException from acquireServer: delay, retry.
    }
}
```

Note: The orphan job warning in finally should only log when the job didn't complete normally.
Adjust the logging condition based on whether the method returned successfully or threw.

**Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests '*VideoVisualizationServiceTest*'`
Expected: PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationService.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt
git commit -m "feat: add VideoVisualizationService orchestrator"
```

---

### Task 9: VideoVisualizationService — Error Scenarios

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/DetectServiceDispatcher.kt` — add job-failed dispatcher variant

**Step 1: Add JobFailedDispatcher to DetectServiceDispatcher.kt**

Add at end of file:

```kotlin
/**
 * Dispatcher that returns a failed job status for testing error scenarios.
 */
class JobFailedDispatcher : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath

        return when {
            path == "/detect/video/visualize" ->
                respondJson(202, """{"job_id":"fail-job","status":"queued","message":"Created"}""")

            path.matches(Regex("/jobs/[^/]+")) && !path.contains("/download") ->
                respondJson(200, """{"job_id":"fail-job","status":"failed","progress":0,"created_at":"2026-02-16T12:00:00Z","error":"Out of GPU memory"}""")

            else -> respondJson(404, """{"detail":"Not Found"}""")
        }
    }

    private fun respondJson(status: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(status)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .body(body)
            .build()
}
```

**Step 2: Write error scenario tests**

Add to `VideoVisualizationServiceTest.kt`:

```kotlin
    @Test
    fun `annotateVideo throws VideoAnnotationFailedException when job fails`() =
        runBlocking {
            mockWebServer.dispatcher = ru.zinin.frigate.analyzer.core.testsupport.JobFailedDispatcher()

            val exception = assertFailsWith<VideoAnnotationFailedException> {
                val testVideoPath = Files.createTempFile("test-input-", ".mp4")
                Files.write(testVideoPath, byteArrayOf(1, 2, 3))
                service.annotateVideo(
                    videoPath = testVideoPath,
                )
            }

            assertTrue(exception.message!!.contains("Out of GPU memory"))
            // Server should be released
            assertEquals(0, registry.getServer("test")!!.processingVideoVisualizeRequestsCount.get())
        }

    @Test
    fun `annotateVideo throws DetectTimeoutException on timeout`() =
        runBlocking {
            // Dispatcher that always returns "processing" status
            mockWebServer.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/detect/video/visualize" ->
                            mockwebserver3.MockResponse.Builder()
                                .code(202)
                                .addHeader("Content-Type", "application/json")
                                .body("""{"job_id":"slow-job","status":"queued","message":"Created"}""")
                                .build()
                        path.matches(Regex("/jobs/[^/]+")) && !path.contains("/download") ->
                            mockwebserver3.MockResponse.Builder()
                                .code(200)
                                .addHeader("Content-Type", "application/json")
                                .body("""{"job_id":"slow-job","status":"processing","progress":50,"created_at":"2026-02-16T12:00:00Z"}""")
                                .build()
                        else ->
                            mockwebserver3.MockResponse.Builder().code(404).build()
                    }
                }
            }

            // Use short timeout service
            val shortTimeoutProps = DetectProperties(
                videoVisualize = VideoVisualizeConfig(
                    timeout = Duration.ofMillis(500),
                    pollInterval = Duration.ofMillis(50),
                ),
            )
            val shortTimeoutService = VideoVisualizationService(
                DetectService(buildWebClient(), loadBalancer, shortTimeoutProps),
                loadBalancer,
                shortTimeoutProps,
            )

            assertFailsWith<DetectTimeoutException> {
                shortTimeoutService.annotateVideo(
                    bytes = byteArrayOf(1, 2, 3),
                    filePath = "/test/video.mp4",
                )
            }

            // Server should be released
            assertEquals(0, registry.getServer("test")!!.processingVideoVisualizeRequestsCount.get())
        }
```

**Step 3: Add submit retry test (review decision S8)**

Add test that verifies retry on transient submit failure:
```kotlin
    @Test
    fun `annotateVideo retries submit on transient server error`() =
        runBlocking {
            // Dispatcher: first POST returns 500, second POST returns 202, then normal flow
            // Use ConfigurableDetectServiceDispatcher or custom dispatcher with failCount
            // Verify: job completes successfully after retry, server is released
        }
```

Add import to test file:
```kotlin
import kotlin.test.assertFailsWith
```

**Step 4: Run tests**

Run: `./gradlew :core:test --tests '*VideoVisualizationServiceTest*'`
Expected: All PASS

**Step 5: Commit**

```bash
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/DetectServiceDispatcher.kt
git commit -m "test: add error scenario tests for VideoVisualizationService"
```

---

### Task 10: Final Build

**Step 1: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (all tests pass, ktlint clean)

If ktlint fails: `./gradlew ktlintFormat` then retry build.
