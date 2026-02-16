# FA-18: Video Visualize — DetectService Extension Design

## Context

Jira: [FA-18](https://jira.zinin.ru/browse/FA-18) — Команда для выгрузки видео с выделенными объектами.

Detection server API v2.2.0 добавляет async job-based endpoint для аннотации видео:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/detect/video/visualize` | POST | Submit video → get `job_id` (HTTP 202) |
| `/jobs/{job_id}` | GET | Poll status (progress 0-100%) |
| `/jobs/{job_id}/download` | GET | Download annotated video |

Текущий scope: расширение DetectService, response-модели, load balancer. Telegram-команда — отдельная задача.

## Decision: Architecture Approach

Три метода в `DetectService` (HTTP-клиент) + `VideoVisualizationService` (оркестратор с одним публичным методом).

Отклонённые подходы:
- Один монолитный метод в DetectService — смешивает HTTP-вызовы и бизнес-логику polling
- Отдельный VideoDetectService — дублирование паттернов WebClient

## Design

### 1. Response Models (module: `model`)

Новые файлы в `ru.zinin.frigate.analyzer.model.response`:

**JobStatus.kt:**
```kotlin
enum class JobStatus {
    @JsonProperty("queued") QUEUED,
    @JsonProperty("processing") PROCESSING,
    @JsonProperty("completed") COMPLETED,
    @JsonProperty("failed") FAILED,
}
```

**JobCreatedResponse.kt:**
```kotlin
data class JobCreatedResponse(
    val jobId: String,
    val status: String,
    val message: String,
)
```

**JobStatusResponse.kt:**
```kotlin
data class JobStatusResponse(
    val jobId: String,
    val status: JobStatus,
    val progress: Int,         // 0-100
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

### 2. RequestType & Load Balancer

**RequestType** — новое значение `VIDEO_VISUALIZE`.

**ServerState** — новый счётчик `processingVideoVisualizeRequestsCount: AtomicInteger`.
Обновить `getCounter()`, `getRequestConfig()`, `canAcceptRequest()`, `getCurrentCount()`, `getMaxCount()`.

**DetectServerProperties** — новое поле `videoVisualizeRequests: RequestConfig`.

**DetectServerLoadBalancer.getAllServersStatistics()** — добавить `videoVisualizeRequests` в `DetectServerStatistics`.

**DetectServerStatistics** (module: `model`) — новое поле `videoVisualizeRequests: ServerLoad`.

### 3. DetectProperties — новый config block

```kotlin
val videoVisualize: VideoVisualizeConfig = VideoVisualizeConfig()

data class VideoVisualizeConfig(
    val timeout: Duration = Duration.ofMinutes(15),
    val pollInterval: Duration = Duration.ofSeconds(3),
    val maxDet: Int = 100,
    val detectEvery: Int? = null,
    val lineWidth: Int = 2,
    val showLabels: Boolean = true,
    val showConf: Boolean = true,
)
```

### 4. DetectService — три новых метода

```kotlin
// Submit video for annotation on a specific server.
// Does NOT acquire or release server — caller manages slot lifecycle.
suspend fun submitVideoVisualize(
    acquired: AcquiredServer,
    bytes: ByteArray,
    filePath: String,
    conf: Double, imgsz: Int, maxDet: Int,
    detectEvery: Int?, classes: String?,
    lineWidth: Int, showLabels: Boolean, showConf: Boolean,
    model: String,
): JobCreatedResponse

// Poll job status on a specific server (no load balancer involved).
suspend fun getJobStatus(acquired: AcquiredServer, jobId: String): JobStatusResponse

// Download annotated video from a specific server. Streams to temp file.
suspend fun downloadJobResult(acquired: AcquiredServer, jobId: String): Path
```

Все три метода принимают `AcquiredServer` как параметр — НЕ управляют слотами. Slot lifecycle управляется вызывающим кодом (`VideoVisualizationService`).

`getJobStatus` и `downloadJobResult` обращаются к конкретному серверу через `acquired.schema/host/port` — load balancer не участвует.

`downloadJobResult` использует streaming запись во временный файл для избежания OOM на больших видео.

### 5. VideoVisualizationService — оркестратор

Один публичный метод. Управляет slot lifecycle (acquire/release) и оркестрирует три фазы.

```kotlin
@Service
class VideoVisualizationService(
    private val detectService: DetectService,
    private val loadBalancer: DetectServerLoadBalancer,
    private val detectProperties: DetectProperties,
) {
    suspend fun annotateVideo(
        bytes: ByteArray,
        filePath: String,
        conf: Double = detectProperties.detect.defaultConfidence,
        // ... остальные params с defaults из detectProperties.videoVisualize
        onProgress: suspend (JobStatusResponse) -> Unit = {},
    ): Path
}
```

**Возвращает:** `Path` к временному файлу с аннотированным видео. Caller отвечает за удаление файла.

**Destination:** Standalone сервис. Вызывается из Telegram-команды (отдельная задача), видео отправляется пользователю, временный файл удаляется.

#### Внутренняя логика — три фазы:

**Slot management — оркестратор управляет lifecycle:**
```
val acquired = loadBalancer.acquireServer(RequestType.VIDEO_VISUALIZE)
try {
    // Фаза 1, 2, 3
} finally {
    loadBalancer.releaseServer(acquired.id, RequestType.VIDEO_VISUALIZE)
    // Если job не завершился (timeout/cancel) — логируем orphan:
    // logger.warn { "Orphan job $jobId may still be running on server ${acquired.id}" }
}
```

**Фаза 1: SUBMIT (с retry между серверами)**
```
withTimeout(videoVisualize.timeout) {
    retrySubmit: while (true) {
        try {
            val acquired = loadBalancer.acquireServer(VIDEO_VISUALIZE)
            val job = detectService.submitVideoVisualize(acquired, bytes, ...)
        } catch (e: Exception) {
            loadBalancer.releaseServer(acquired.id, VIDEO_VISUALIZE)
            // Если DetectServerUnavailableException → delay, retry
            // retry по аналогии с существующими методами DetectService
        }
    }
}
```

**Фаза 2: POLL (на фиксированном сервере)**
```
while (true) {
    delay(pollInterval)
    val status = detectService.getJobStatus(acquired, job.jobId)
    onProgress(status)
    when (status.status) {
        JobStatus.COMPLETED -> break
        JobStatus.FAILED -> throw VideoAnnotationFailedException(status.error)
        // QUEUED, PROCESSING → continue polling
    }
}
```

**Фаза 3: DOWNLOAD (с того же сервера)**
```
val videoPath = detectService.downloadJobResult(acquired, job.jobId)
return videoPath
```

#### Сценарии

| Сценарий | Поведение |
|----------|-----------|
| Happy path | acquire → submit → poll (onProgress) → download → release → return Path |
| Submit HTTP error | release server, retry с другим сервером |
| No servers | delay, retry (DetectServerUnavailableException) |
| Job failed | throw VideoAnnotationFailedException, release |
| Server down during poll | retry poll, при общем таймауте → DetectTimeoutException |
| Coroutine cancelled | CancellationException, finally → release + log orphan job |
| Total timeout | withTimeout → DetectTimeoutException, release + log orphan job |

### 6. application.yaml changes

Новый блок в `application.detect`:
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

Новое поле в конфигурации каждого detect-сервера:
```yaml
video-visualize-requests:
  simultaneous-count: 1
  priority: 1
```

### 7. Exception

Новый `VideoAnnotationFailedException` в module `model`:
```kotlin
class VideoAnnotationFailedException(message: String?, cause: Throwable? = null)
    : RuntimeException(message, cause)
```

### 8. OpenAPI spec

Сохранить загруженный `openapi.json` (v2.2.0) в `docs/openapi/detect-server-openapi.json` для справки.
