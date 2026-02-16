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
    val status: String,       // queued, processing, completed, failed
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
// Submit video for annotation. Returns acquired server + job info.
// Caller MUST release server via loadBalancer.releaseServer() when done.
suspend fun submitVideoVisualize(
    bytes: ByteArray,
    filePath: String,
    conf: Double, imgsz: Int, maxDet: Int,
    detectEvery: Int?, classes: String?,
    lineWidth: Int, showLabels: Boolean, showConf: Boolean,
    model: String,
): Pair<AcquiredServer, JobCreatedResponse>

// Poll job status on a specific server (no load balancer involved).
suspend fun getJobStatus(acquired: AcquiredServer, jobId: String): JobStatusResponse

// Download annotated video from a specific server.
suspend fun downloadJobResult(acquired: AcquiredServer, jobId: String): ByteArray
```

`submitVideoVisualize` делает `acquireServer(VIDEO_VISUALIZE)` и НЕ делает release — возвращает `AcquiredServer`. Слот удерживается до завершения job.

`getJobStatus` и `downloadJobResult` обращаются к конкретному серверу через `acquired.schema/host/port` — load balancer не участвует.

### 5. VideoVisualizationService — оркестратор

Один публичный метод:

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
    ): ByteArray
}
```

#### Внутренняя логика — три фазы:

**Фаза 1: SUBMIT (с retry между серверами)**
```
withTimeout(videoVisualize.timeout) {
    retrySubmit: while (true) {
        val (acquired, job) = detectService.submitVideoVisualize(bytes, ...)
        // Если HTTP-ошибка → release server, delay, retry на другом сервере
        // Если DetectServerUnavailableException → delay, retry
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
        "completed" -> break
        "failed" -> throw VideoAnnotationFailedException(status.error)
        // queued, processing → continue polling
    }
}
```

**Фаза 3: DOWNLOAD (с того же сервера)**
```
val videoBytes = detectService.downloadJobResult(acquired, job.jobId)
return videoBytes
```

**Release — ВСЕГДА в finally:**
```
finally {
    loadBalancer.releaseServer(acquired.id, RequestType.VIDEO_VISUALIZE)
}
```

#### Сценарии

| Сценарий | Поведение |
|----------|-----------|
| Happy path | submit → poll (onProgress) → download → release → return ByteArray |
| Submit HTTP error | release server, retry с другим сервером |
| No servers | delay, retry (DetectServerUnavailableException) |
| Job failed | throw VideoAnnotationFailedException, release |
| Server down during poll | retry poll, при общем таймауте → DetectTimeoutException |
| Coroutine cancelled | CancellationException, finally → release |
| Total timeout | withTimeout → DetectTimeoutException, release |

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
