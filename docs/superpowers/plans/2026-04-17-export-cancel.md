# Export Cancellation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user-initiated cancellation for QuickExport and `/export` flows via the new vision-server `POST /jobs/{id}/cancel` endpoint.

**Architecture:** A new in-memory `ActiveExportRegistry` tracks active exports by synthetic `exportId` for the **execution phase** (submit → poll → download → send → cancel). The existing `ActiveExportTracker` is **kept** as the **dialog-phase lock** in `/export` — it prevents two parallel `/export` dialogs in the same DM from hijacking each other's waiter replies (registry has no hook into the dialog phase because `exportId` is generated only after dialog completion). A new `CancelExportHandler` processes `xc:{exportId}` callbacks — it atomically transitions the registry entry to CANCELLING, updates the Telegram keyboard, cancels the coroutine, and fire-and-forget-cancels the vision-server job via a new `CancellableJob` SAM plumbed through `VideoExportService` → `VideoVisualizationService`.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, WebFlux, Kotlin Coroutines, ktgbotapi, JUnit 5, kotlin.test, mockk, mockwebserver3.

**Spec:** `docs/superpowers/specs/2026-04-17-export-cancel-design.md` (commit `c195617`).

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/CancellableJob.kt` | SAM: abstraction over "cancel the running vision-server annotation job". Hides `AcquiredServer` inside core-module. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistry.kt` | In-memory registry of active exports, keyed by `exportId`. Atomic dedup per recordingId (QuickExport) and per chatId (/export). Atomic CAS for `ACTIVE → CANCELLING`. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportCoroutineScope.kt` | Shared `CoroutineScope` bean used by `QuickExportHandler`, `ExportExecutor`, and `CancelExportHandler`. `@PreDestroy`-cancelled. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/cancel/CancelExportHandler.kt` | Processes `xc:{exportId}` (real cancel) and `np:{exportId}` (silent noop-ack for progress-button taps). |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistryTest.kt` | Tests for registry. |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/cancel/CancelExportHandlerTest.kt` | Tests for cancel handler. |

### Modified files

| Path | Change |
|---|---|
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectProperties.kt` | Add `VideoVisualizeConfig.cancelTimeout: Duration = 10s`. |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt` | Add `suspend fun cancelJob(server: AcquiredServer, jobId: String)`. |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationService.kt` | Add `onJobSubmitted: suspend (CancellableJob) -> Unit = {}` parameter, invoke inside `withContext(NonCancellable)` post-submit. |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt` | Add `onJobSubmitted` param; plumb into `annotate(...)` only for ANNOTATED mode. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt` | Add `onJobSubmitted: suspend (CancellableJob) -> Unit = {}` to both methods. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt` | Remove local `activeExports` set; use `ActiveExportRegistry`; launch job with `CoroutineStart.LAZY`; add cancel keyboard (two rows: progress + cancel); `np:` noop callback on progress button; cancellation UI branch in catch; constructor takes shared `ExportCoroutineScope` bean. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt` | Wrap body in `exportScope.launch(start = LAZY)` with registry.tryStart + `job.join()`; add `[✖ Отмена]` keyboard to status message; cancellation UI branch; plumb `onJobSubmitted` → `registry.attachCancellable`. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportCommandHandler.kt` | **Keep** `ActiveExportTracker` usage for dialog-phase lock; remove private `exportScope` (executor owns its own via `ExportCoroutineScope` bean). |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt` | Add second `onDataCallbackQuery` route for prefixes `xc:` and `np:` → `CancelExportHandler.handle(callback)`. |
| `modules/telegram/src/main/resources/messages_ru.properties` | New keys (cancel.*, quickexport.button.cancel, quickexport.progress.cancelling, quickexport.cancelled, export.button.cancel, export.progress.cancelling, export.cancelled.action — see Task 6). |
| `modules/telegram/src/main/resources/messages_en.properties` | Same new keys in English. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/DetectServiceDispatcher.kt` | Add handling for `POST /jobs/{id}/cancel` → 200 with status response. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt` | If does not exist, create; add tests for `cancelJob`. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt` | Add tests for `onJobSubmitted` callback invocation. |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt` | Update mocks to 5-arg `exportByRecordingId` signature; add scenarios for cancel keyboard and cancellation path. |
| `.claude/rules/telegram.md` | Add "Cancellation" subsection in Quick Export. |
| `.claude/rules/configuration.md` | Add `DETECT_VIDEO_VISUALIZE_CANCEL_TIMEOUT`. |

### Deleted files

None. (Earlier plan iteration had `ActiveExportTracker` deletion; after review-iter-1 it is kept for `/export` dialog-phase lock.)

---

### Task 1: `CancellableJob` SAM + `DetectProperties.cancelTimeout`

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/CancellableJob.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectProperties.kt`

- [ ] **Step 1: Create `CancellableJob.kt`**

```kotlin
package ru.zinin.frigate.analyzer.telegram.service.model

/**
 * Abstraction over cancelling a remote vision-server job.
 *
 * Published from `VideoVisualizationService` via `onJobSubmitted` once the job is accepted
 * by a vision server. Consumed by `CancelExportHandler` via `ActiveExportRegistry`.
 *
 * Hides `AcquiredServer` inside the core module, so the telegram module does not need to
 * reference core/loadbalancer types.
 */
fun interface CancellableJob {
    suspend fun cancel()
}
```

- [ ] **Step 2: Add `cancelTimeout` to `VideoVisualizeConfig`**

Edit `DetectProperties.kt` — replace the existing `VideoVisualizeConfig` block:

```kotlin
data class VideoVisualizeConfig(
    // Must stay below Telegram QuickExport/Export annotated outer timeouts (50m each) so that
    // an annotation timeout surfaces as DetectTimeoutException with a dedicated user message
    // instead of being masked by the outer withTimeoutOrNull.
    val timeout: Duration = Duration.ofMinutes(45),
    val cancelTimeout: Duration = Duration.ofSeconds(10),
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

- [ ] **Step 3: Run build and formatting**

Run: `./gradlew ktlintFormat :model:compileKotlin :common:compileKotlin :service:compileKotlin :telegram:compileKotlin :core:compileKotlin` (delegate to build-runner agent).
Expected: all modules compile.

- [ ] **Step 4: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/CancellableJob.kt \
        modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectProperties.kt
git commit -m "feat(detect): add CancellableJob SAM and cancelTimeout config"
```

---

### Task 2: `DetectService.cancelJob`

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/DetectServiceDispatcher.kt` (add `/cancel` endpoint)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceCancelJobTest.kt` (new)

- [ ] **Step 1: Extend dispatcher with `/cancel` endpoint**

Edit `DetectServiceDispatcher.kt`. Inside the outer `else` block of `dispatch()`, replace:

```kotlin
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

with:

```kotlin
            else -> {
                if (path.matches(Regex("/jobs/[^/]+/download"))) {
                    respondBinary(200, "video/mp4", fakeVideoBytes())
                } else if (path.matches(Regex("/jobs/[^/]+/cancel"))) {
                    if (method == "POST") {
                        respondJson(200, jobStatusCancelledJson())
                    } else {
                        respondJson(405, errorJson("Method Not Allowed"))
                    }
                } else if (path.matches(Regex("/jobs/[^/]+"))) {
                    respondJson(200, jobStatusCompletedJson())
                } else {
                    respondJson(404, errorJson("Not Found"))
                }
            }
```

Add a new private method next to `jobStatusCompletedJson()`:

```kotlin
    private fun jobStatusCancelledJson(): String =
        """
        {
          "job_id": "test-job-123",
          "status": "cancelled",
          "progress": 50,
          "created_at": "2026-02-16T12:00:00Z",
          "completed_at": null,
          "download_url": null,
          "error": null,
          "stats": null
        }
        """.trimIndent()
```

- [ ] **Step 2: Write failing tests for `cancelJob`**

Create `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceCancelJobTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.service

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.core.config.properties.DetectServerProperties
import ru.zinin.frigate.analyzer.core.config.properties.RequestConfig
import ru.zinin.frigate.analyzer.core.config.properties.VideoVisualizeConfig
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.loadbalancer.AcquiredServer
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerRegistry
import ru.zinin.frigate.analyzer.core.loadbalancer.RequestType
import ru.zinin.frigate.analyzer.core.loadbalancer.ServerHealthMonitor
import ru.zinin.frigate.analyzer.core.loadbalancer.ServerSelectionStrategy
import ru.zinin.frigate.analyzer.core.testsupport.DetectServiceDispatcher
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class DetectServiceCancelJobTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var service: DetectService
    private lateinit var acquired: AcquiredServer

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = DetectServiceDispatcher()
        mockWebServer.start()

        val serverProps = DetectServerProperties(
            schema = "http",
            host = "localhost",
            port = mockWebServer.port,
            frameRequests = RequestConfig(simultaneousCount = 1, priority = 0),
            framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 0),
            visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
            videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
        )
        val appProps = ApplicationProperties(detectServers = mapOf("test" to serverProps))
        val registry = DetectServerRegistry()
        registry.register("test", serverProps)
        registry.getServer("test")!!.alive = true

        val detectProperties = DetectProperties(
            videoVisualize = VideoVisualizeConfig(cancelTimeout = Duration.ofSeconds(2)),
        )
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

        val webClient = WebClient.builder()
            .exchangeStrategies(
                ExchangeStrategies.builder()
                    .codecs {
                        it.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(buildObjectMapper()))
                        it.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(buildObjectMapper()))
                    }.build(),
            ).build()

        val loadBalancer = DetectServerLoadBalancer(
            appProps,
            registry,
            ServerSelectionStrategy(),
            ServerHealthMonitor(registry, webClient, clock, detectProperties),
        )

        val tempFileHelper = TempFileHelper(appProps, clock)
        tempFileHelper.init()
        service = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper, buildObjectMapper())
        acquired = AcquiredServer(
            id = "test",
            schema = "http",
            host = "localhost",
            port = mockWebServer.port,
            requestType = RequestType.VIDEO_VISUALIZE,
        )
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.close()
    }

    @Test
    fun `cancelJob sends POST to jobs cancel endpoint and returns normally on 200`() = runBlocking {
        service.cancelJob(acquired, "abc-123")
        val request = mockWebServer.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/jobs/abc-123/cancel", request.url.encodedPath)
    }

    @Test
    fun `cancelJob tolerates 409 (already terminal)`() = runBlocking {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder()
                    .code(409)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"detail":"job already completed"}""")
                    .build()
        }
        // Does not throw.
        service.cancelJob(acquired, "abc-123")
    }

    @Test
    fun `cancelJob tolerates 500`() = runBlocking {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder()
                    .code(500)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"detail":"boom"}""")
                    .build()
        }
        service.cancelJob(acquired, "abc-123")
    }

    @Test
    fun `cancelJob tolerates timeout`() = runBlocking {
        val hangCount = AtomicInteger(0)
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hangCount.incrementAndGet()
                Thread.sleep(5_000) // longer than cancelTimeout=2s
                return MockResponse.Builder().code(200).body("{}").build()
            }
        }
        service.cancelJob(acquired, "abc-123")
        // The call returns normally within cancelTimeout (2s) even though server hangs.
    }

    @Test
    fun `cancelJob rethrows parent CancellationException (not TimeoutCancellation)`() = runBlocking {
        // Guards design §5.1 + iter-2 codex TEST-2 requirement: cancelJob must not swallow
        // a parent-scope cancellation. The catch (TimeoutCancellationException) before
        // catch (CancellationException) ensures timeouts are logged, but external (parent)
        // cancellation must propagate so the caller's shutdown / user-cancel path works.
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(10_000) // longer than cancelTimeout + our own cancel
                return MockResponse.Builder().code(200).body("{}").build()
            }
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = scope.launch {
            started.complete(Unit)
            service.cancelJob(acquired, "abc-123")
        }
        started.await()
        kotlinx.coroutines.delay(100) // let the request actually fly
        job.cancel()
        job.join()
        // If cancelJob swallowed the CancellationException, job.isCancelled would still be true
        // but the job would have completed "normally" — we assert the cancel propagated.
        assertEquals(true, job.isCancelled)
        scope.cancel()
    }

    private fun buildObjectMapper(): tools.jackson.databind.ObjectMapper =
        JsonMapper
            .builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build()
}
```

- [ ] **Step 3: Run tests to verify they fail**

Delegate to build-runner: `./gradlew :core:test --tests "ru.zinin.frigate.analyzer.core.service.DetectServiceCancelJobTest"`.
Expected: all 4 tests fail with `cancelJob` not defined.

- [ ] **Step 4: Implement `cancelJob`**

Edit `DetectService.kt`. Add imports at the top:

```kotlin
import kotlinx.coroutines.TimeoutCancellationException
```

(if not already imported — it already is). After `downloadJobResult(...)` method, add:

```kotlin
    /**
     * Requests cancellation of a running vision-server job.
     * Fire-and-forget semantics: tolerant of 409 (already terminal), 5xx, timeouts, and network errors.
     * Only `CancellationException` propagates up to the caller.
     */
    suspend fun cancelJob(
        acquired: AcquiredServer,
        jobId: String,
    ) {
        try {
            withTimeout(detectProperties.videoVisualize.cancelTimeout.toMillis()) {
                webClient
                    .post()
                    .uri { uriBuilder ->
                        uriBuilder
                            .scheme(acquired.schema)
                            .host(acquired.host)
                            .port(acquired.port)
                            .path("/jobs/{jobId}/cancel")
                            .build(jobId)
                    }.retrieve()
                    .bodyToMono<JobStatusResponse>()
                    .awaitSingleOrNull()
            }
            logger.info { "Cancel request accepted for job $jobId on ${acquired.id}" }
        } catch (e: TimeoutCancellationException) {
            // MUST come before CancellationException catch: TimeoutCancellationException is a subtype,
            // otherwise timeouts would be rethrown as cancellation instead of logged as WARN.
            logger.warn { "Cancel request timed out for job $jobId on ${acquired.id}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebClientResponseException) {
            if (e.statusCode.value() == 409) {
                logger.info { "Cancel: job $jobId already terminal (409)" }
            } else {
                logger.warn { "Cancel failed for job $jobId on ${acquired.id}: ${e.statusCode} ${e.message}" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Cancel request error for job $jobId on ${acquired.id}" }
        }
    }
```

- [ ] **Step 4b: Fix `downloadJobResult` to clean temp-file on CancellationException**

Temp-файл download'а утекает при cancel, так как `catch (Exception)` в Kotlin-корутинах **не** ловит `CancellationException`. Дополнительно: `tempFileHelper.deleteIfExists(...)` — `suspend` и внутри делает `withContext(Dispatchers.IO)`. В уже отменённой корутине любой suspend-вызов **мгновенно** бросает новый `CancellationException` без реальной работы — cleanup не выполнится. Поэтому обязательно обернуть в `withContext(NonCancellable)`.

Добавить импорт `import kotlinx.coroutines.NonCancellable` и `import kotlinx.coroutines.withContext` (если их ещё нет). Заменить блок в `downloadJobResult`:

```kotlin
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(tempFile)
            throw e
        }
```

на:

```kotlin
        } catch (e: CancellationException) {
            withContext(NonCancellable) { tempFileHelper.deleteIfExists(tempFile) }
            throw e
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(tempFile)
            throw e
        }
```

- [ ] **Step 5: Run tests to verify they pass**

`./gradlew :core:test --tests "ru.zinin.frigate.analyzer.core.service.DetectServiceCancelJobTest"` (via build-runner).
Expected: all 4 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/DetectServiceDispatcher.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceCancelJobTest.kt
git commit -m "feat(detect): add DetectService.cancelJob with 409/5xx/timeout tolerance"
```

---

### Task 3: `VideoVisualizationService.annotateVideo` — add `onJobSubmitted`

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationService.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt`

- [ ] **Step 1: Add failing test for onJobSubmitted happy path**

Append to `VideoVisualizationServiceTest.kt` (before the closing `}` of the class):

```kotlin
    @Test
    fun `annotateVideo invokes onJobSubmitted once after successful submit`() =
        runBlocking {
            val testVideoPath = Files.createTempFile(tempDir, "test-input-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))
            val invocations = mutableListOf<String>()
            try {
                val result = service.annotateVideo(
                    videoPath = testVideoPath,
                    onJobSubmitted = { cancellable ->
                        invocations.add(cancellable.toString())
                    },
                )
                assertEquals(1, invocations.size, "onJobSubmitted must fire exactly once")
                Files.deleteIfExists(result)
            } finally {
                Files.deleteIfExists(testVideoPath)
            }
            Unit
        }

    @Test
    fun `annotateVideo invokes onJobSubmitted even when parent coroutine is being cancelled (NonCancellable)`() =
        runBlocking {
            // Guards design §2.3 "NonCancellable" invariant and iter-2 codex TEST-2: the callback
            // publication must survive a user-cancel that arrives simultaneously with submit
            // completion — otherwise the handler never learns (server, jobId) and can't POST /cancel.
            val testVideoPath = Files.createTempFile(tempDir, "test-input-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))
            val callbackFired = kotlinx.coroutines.CompletableDeferred<Unit>()
            try {
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
                val job = scope.launch {
                    try {
                        service.annotateVideo(
                            videoPath = testVideoPath,
                            onJobSubmitted = {
                                callbackFired.complete(Unit)
                                // Give the parent a chance to observe cancellation mid-callback.
                                kotlinx.coroutines.delay(50)
                            },
                        )
                    } catch (_: CancellationException) {
                        // Expected path — we cancelled externally.
                    }
                }
                // Wait for callback to fire, THEN cancel the parent.
                kotlinx.coroutines.withTimeout(5_000) { callbackFired.await() }
                job.cancel()
                job.join()
                // If onJobSubmitted had NOT been wrapped in NonCancellable, cancelling the parent
                // before the callback resumed would have thrown CancellationException inside it,
                // skipping the completion. Successful await above proves the callback fully ran.
                assertEquals(true, callbackFired.isCompleted)
                scope.cancel()
            } finally {
                Files.deleteIfExists(testVideoPath)
            }
            Unit
        }

    @Test
    fun `annotateVideo does not invoke onJobSubmitted when submit never succeeds`() =
        runBlocking {
            mockWebServer.dispatcher =
                object : mockwebserver3.Dispatcher() {
                    override fun dispatch(request: mockwebserver3.RecordedRequest) =
                        mockwebserver3.MockResponse.Builder().code(500).body("boom").build()
                }
            val shortDetectProperties =
                DetectProperties(
                    retryDelay = Duration.ofMillis(10),
                    videoVisualize =
                        VideoVisualizeConfig(
                            timeout = Duration.ofMillis(200),
                            pollInterval = Duration.ofMillis(50),
                        ),
                )
            val shortAppProps = applicationProperties(DetectServerProperties(
                schema = "http",
                host = "localhost",
                port = mockWebServer.port,
                frameRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
            ))
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val shortLoadBalancer = DetectServerLoadBalancer(
                shortAppProps,
                registry,
                ServerSelectionStrategy(),
                ServerHealthMonitor(registry, webClient, clock, shortDetectProperties),
            )
            val shortTempFileHelper = TempFileHelper(shortAppProps, clock)
            shortTempFileHelper.init()
            val shortDetectService =
                DetectService(webClient, shortLoadBalancer, shortDetectProperties, shortTempFileHelper, buildObjectMapper())
            val shortService = VideoVisualizationService(shortDetectService, shortLoadBalancer, shortDetectProperties)

            val testVideoPath = Files.createTempFile(tempDir, "test-input-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))
            var invoked = false
            try {
                assertThrows<DetectTimeoutException> {
                    shortService.annotateVideo(
                        videoPath = testVideoPath,
                        onJobSubmitted = { invoked = true },
                    )
                }
                assertEquals(false, invoked, "onJobSubmitted must not fire when submit never succeeds")
            } finally {
                Files.deleteIfExists(testVideoPath)
            }
            Unit
        }
```

- [ ] **Step 2: Run tests to verify they fail**

`./gradlew :core:test --tests "ru.zinin.frigate.analyzer.core.service.VideoVisualizationServiceTest"` (build-runner).
Expected: new tests fail with "onJobSubmitted: unresolved reference".

- [ ] **Step 3: Add `onJobSubmitted` param to `annotateVideo`**

Edit `VideoVisualizationService.kt`. Add import:

```kotlin
import kotlinx.coroutines.NonCancellable
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
```

Change the `annotateVideo` signature and body:

```kotlin
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
        onJobSubmitted: suspend (CancellableJob) -> Unit = {},
    ): Path {
```

Inside `withTimeout(timeoutMs) { ... }`, just after `jobId = job.jobId`, add:

```kotlin
                    // Publish cancellable to caller under NonCancellable so that if user-cancellation
                    // races with submit completion, the caller still learns the (server, jobId) pair
                    // and can call /jobs/{id}/cancel on the vision server.
                    val cancellable = CancellableJob { detectService.cancelJob(server, job.jobId) }
                    withContext(NonCancellable) { onJobSubmitted(cancellable) }
```

So the full withTimeout block becomes:

```kotlin
            val result =
                withTimeout(timeoutMs) {
                    val (server, job) =
                        submitWithRetry(
                            videoPath,
                            conf,
                            imgSize,
                            maxDet,
                            detectEvery,
                            classes,
                            lineWidth,
                            showLabels,
                            showConf,
                            model,
                        )
                    acquired = server
                    jobId = job.jobId

                    logger.info { "Video annotation job ${job.jobId} submitted to server ${server.id}" }

                    val cancellable = CancellableJob { detectService.cancelJob(server, job.jobId) }
                    withContext(NonCancellable) { onJobSubmitted(cancellable) }

                    pollUntilDone(server, job.jobId, pollIntervalMs, onProgress)

                    detectService.downloadJobResult(server, job.jobId)
                }
```

- [ ] **Step 4: Run tests to verify they pass**

`./gradlew :core:test --tests "ru.zinin.frigate.analyzer.core.service.VideoVisualizationServiceTest"` (build-runner).
Expected: all tests (new + existing) PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationService.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt
git commit -m "feat(core): plumb onJobSubmitted callback through annotateVideo"
```

---

### Task 4: `VideoExportService` — plumb `onJobSubmitted`

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt`

- [ ] **Step 1: Update interface**

Edit `VideoExportService.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.service

import ru.zinin.frigate.analyzer.model.dto.CameraRecordingCountDto
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface VideoExportService {
    suspend fun findCamerasWithRecordings(
        startInstant: Instant,
        endInstant: Instant,
    ): List<CameraRecordingCountDto>

    suspend fun exportVideo(
        startInstant: Instant,
        endInstant: Instant,
        camId: String,
        mode: ExportMode = ExportMode.ORIGINAL,
        onProgress: suspend (VideoExportProgress) -> Unit = {},
        onJobSubmitted: suspend (CancellableJob) -> Unit = {},
    ): Path

    suspend fun cleanupExportFile(path: Path)

    /**
     * Exports video by recording ID within ±[duration] range from recordTimestamp.
     *
     * @param recordingId recording UUID from the database
     * @param duration one-side duration (default 1 minute, total range is 2 minutes)
     * @param onProgress progress callback
     * @param onJobSubmitted callback invoked once (only for `mode == ANNOTATED`) as soon as the
     *   vision server has accepted the annotation job, delivering a handle that cancels the job.
     * @return path to the exported video file
     * @throws IllegalArgumentException if the recording is not found or duration is negative
     * @throws IllegalStateException if the recording has no camId or recordTimestamp
     */
    suspend fun exportByRecordingId(
        recordingId: UUID,
        duration: Duration = Duration.ofMinutes(1),
        mode: ExportMode = ExportMode.ORIGINAL,
        onProgress: suspend (VideoExportProgress) -> Unit = {},
        onJobSubmitted: suspend (CancellableJob) -> Unit = {},
    ): Path
}
```

- [ ] **Step 2: Update implementation**

Edit `VideoExportServiceImpl.kt`. Add import:

```kotlin
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
```

Update `exportVideo` signature:

```kotlin
    override suspend fun exportVideo(
        startInstant: Instant,
        endInstant: Instant,
        camId: String,
        mode: ExportMode,
        onProgress: suspend (VideoExportProgress) -> Unit,
        onJobSubmitted: suspend (CancellableJob) -> Unit,
    ): Path {
```

Replace the annotate call site (the `if (mode == ExportMode.ANNOTATED) return annotate(...)` line) with:

```kotlin
            if (mode == ExportMode.ANNOTATED) {
                return annotate(mergedFile, onProgress, onJobSubmitted)
            }
```

Update the `private suspend fun annotate(...)` signature, forward the callback, AND add a dedicated `CancellationException` cleanup branch (otherwise the merged/compressed temp file leaks on user-cancel or shutdown — `catch (Exception)` does NOT catch `CancellationException` in coroutines):

```kotlin
    private suspend fun annotate(
        originalPath: Path,
        onProgress: suspend (VideoExportProgress) -> Unit,
        onJobSubmitted: suspend (CancellableJob) -> Unit,
    ): Path {
        onProgress(VideoExportProgress(Stage.ANNOTATING, percent = 0))

        val allowedClassesCsv =
            detectionFilterProperties.allowedClasses
                .filter { it.isNotBlank() }
                .joinToString(",")
                .ifEmpty { null }

        logger.debug { "Starting annotation: model=${detectProperties.goodModel}, classes=$allowedClassesCsv" }
        try {
            val annotatedPath =
                videoVisualizationService.annotateVideo(
                    videoPath = originalPath,
                    classes = allowedClassesCsv,
                    model = detectProperties.goodModel,
                    onProgress = { status ->
                        logger.debug { "Annotation progress: ${status.progress}%" }
                        onProgress(VideoExportProgress(Stage.ANNOTATING, percent = status.progress))
                    },
                    onJobSubmitted = onJobSubmitted,
                )
            logger.debug { "Annotation complete: $annotatedPath" }
            tempFileHelper.deleteIfExists(originalPath)
            logger.debug { "Deleted intermediate file: $originalPath" }
            return annotatedPath
        } catch (e: CancellationException) {
            logger.debug(e) { "Annotation cancelled, cleaning up: $originalPath" }
            withContext(NonCancellable) { tempFileHelper.deleteIfExists(originalPath) }
            throw e
        } catch (e: Exception) {
            logger.debug(e) { "Annotation failed, cleaning up: $originalPath" }
            withContext(NonCancellable) { tempFileHelper.deleteIfExists(originalPath) }
            throw e
        }
    }
```

Add import `import kotlinx.coroutines.CancellationException` if not already present.

Update `exportByRecordingId` signature and forward onJobSubmitted:

```kotlin
    override suspend fun exportByRecordingId(
        recordingId: UUID,
        duration: Duration,
        mode: ExportMode,
        onProgress: suspend (VideoExportProgress) -> Unit,
        onJobSubmitted: suspend (CancellableJob) -> Unit,
    ): Path {
        require(!duration.isNegative && !duration.isZero) { "duration must be positive" }

        logger.debug { "exportByRecordingId started: recordingId=$recordingId, duration=$duration" }

        val recording =
            recordingRepository.findById(recordingId)
                ?: throw IllegalArgumentException("Recording not found: $recordingId")

        val camId =
            recording.camId
                ?: throw IllegalStateException("Recording $recordingId has no camId")

        val recordTimestamp =
            recording.recordTimestamp
                ?: throw IllegalStateException("Recording $recordingId has no recordTimestamp")

        val startInstant = recordTimestamp.minus(duration)
        val endInstant = recordTimestamp.plus(duration)

        logger.debug { "Quick export for recording $recordingId: camId=$camId, range=$startInstant..$endInstant" }

        return exportVideo(
            startInstant = startInstant,
            endInstant = endInstant,
            camId = camId,
            mode = mode,
            onProgress = onProgress,
            onJobSubmitted = onJobSubmitted,
        )
    }
```

- [ ] **Step 3: Write failing test for ANNOTATED plumbing**

Append to `VideoExportServiceImplTest.kt` (before the closing `}` of the test class):

```kotlin
    @Test
    fun `exportVideo plumbs onJobSubmitted through to annotateVideo for ANNOTATED mode`() = runBlocking {
        // Arrange: minimum recording + file stub. Reuse existing test helpers.
        val camId = "cam1"
        val rangeStart = Instant.parse("2026-02-16T12:00:00Z")
        val rangeEnd = Instant.parse("2026-02-16T12:05:00Z")
        val recFile = Files.createTempFile("rec-", ".mp4")
        Files.write(recFile, byteArrayOf(1, 2, 3, 4, 5))
        val recordingEntity = ru.zinin.frigate.analyzer.service.entity.RecordingEntity(
            id = java.util.UUID.randomUUID(),
            camId = camId,
            filePath = recFile.toString(),
            recordTimestamp = rangeStart,
        )
        coEvery { recordingRepository.findByCamIdAndInstantRange(camId, rangeStart, rangeEnd) } returns listOf(recordingEntity)
        val mergedPath = Files.createTempFile("merged-", ".mp4")
        Files.write(mergedPath, ByteArray(100))
        coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedPath
        val annotatedPath = Files.createTempFile("ann-", ".mp4")
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

        // Act
        var received: ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob? = null
        val result = service.exportVideo(
            startInstant = rangeStart,
            endInstant = rangeEnd,
            camId = camId,
            mode = ExportMode.ANNOTATED,
            onProgress = {},
            onJobSubmitted = { received = it },
        )

        // Simulate annotateVideo invoking onJobSubmitted with a test CancellableJob
        val testCancellable = ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob { /* noop */ }
        capturedCallback.captured.invoke(testCancellable)

        // Assert
        assertEquals(annotatedPath, result)
        assertEquals(testCancellable, received)

        Files.deleteIfExists(recFile)
        Files.deleteIfExists(annotatedPath)
    }

    @Test
    fun `exportVideo does not call annotateVideo for ORIGINAL mode`() = runBlocking {
        val camId = "cam1"
        val rangeStart = Instant.parse("2026-02-16T12:00:00Z")
        val rangeEnd = Instant.parse("2026-02-16T12:05:00Z")
        val recFile = Files.createTempFile("rec-", ".mp4")
        Files.write(recFile, byteArrayOf(1, 2, 3))
        val recordingEntity = ru.zinin.frigate.analyzer.service.entity.RecordingEntity(
            id = java.util.UUID.randomUUID(),
            camId = camId,
            filePath = recFile.toString(),
            recordTimestamp = rangeStart,
        )
        coEvery { recordingRepository.findByCamIdAndInstantRange(camId, rangeStart, rangeEnd) } returns listOf(recordingEntity)
        val mergedPath = Files.createTempFile("merged-", ".mp4")
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
        coVerify(exactly = 0) { videoVisualizationService.annotateVideo(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }

        Files.deleteIfExists(recFile)
        Files.deleteIfExists(mergedPath)
    }
```

If imports `slot`, `coVerify`, `coEvery`, `runBlocking`, etc. are missing, add them (the existing test file should already have most). Ensure these imports:

```kotlin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
```

- [ ] **Step 4: Run tests to verify they fail, then pass**

`./gradlew :core:test --tests "ru.zinin.frigate.analyzer.core.service.VideoExportServiceImplTest"` (build-runner).
Expected after step 2: all tests PASS (interface + impl updated together in steps 1 and 2).

- [ ] **Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt \
        modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt
git commit -m "feat(export): plumb onJobSubmitted through VideoExportService"
```

---

### Task 5: `ActiveExportRegistry` (new, with tests)

> **Ordering:** Task 5 **depends on Task 6** — `ActiveExportRegistry` takes `ExportCoroutineScope`
> as a constructor parameter. If you execute tasks strictly in numeric order, Task 5 will not
> compile. **Run Task 6 first, then return to Task 5.** (The numeric order is preserved for spec
> traceability; dependency order overrides it.)

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistry.kt`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistryTest.kt`

- [ ] **Step 1: Write failing tests**

Create `ActiveExportRegistryTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ActiveExportRegistryTest {
    private val scope = ExportCoroutineScope()
    private val registry = ActiveExportRegistry(scope)
    // Use a thread-safe collection: the concurrency test calls newJob() from 32 parallel threads.
    // A plain mutableListOf<Job>() has a data race on add() and can lose job refs → @AfterEach
    // cleanup misses some jobs, causing CI coroutine leaks.
    private val jobs = java.util.concurrent.ConcurrentLinkedQueue<Job>()

    private fun newJob(): Job = Job().also { jobs.add(it) }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        scope.shutdown()
    }

    @Test
    fun `tryStartQuickExport returns Success and stores entry`() {
        val exportId = UUID.randomUUID()
        val chatId = 42L
        val recordingId = UUID.randomUUID()
        val job = newJob()

        val r = registry.tryStartQuickExport(exportId, chatId, ExportMode.ANNOTATED, recordingId, job)

        assertIs<ActiveExportRegistry.StartResult.Success>(r)
        assertEquals(exportId, r.exportId)
        val entry = registry.get(exportId)
        assertNotNull(entry)
        assertEquals(chatId, entry.chatId)
        assertEquals(ExportMode.ANNOTATED, entry.mode)
        assertEquals(recordingId, entry.recordingId)
        assertSame(job, entry.job)
        assertEquals(ActiveExportRegistry.State.ACTIVE, entry.state)
    }

    @Test
    fun `tryStartQuickExport returns DuplicateRecording for same recordingId`() {
        val recordingId = UUID.randomUUID()
        registry.tryStartQuickExport(UUID.randomUUID(), 1L, ExportMode.ORIGINAL, recordingId, newJob())

        val second = registry.tryStartQuickExport(UUID.randomUUID(), 2L, ExportMode.ORIGINAL, recordingId, newJob())

        assertIs<ActiveExportRegistry.StartResult.DuplicateRecording>(second)
    }

    @Test
    fun `tryStartQuickExport allows different recordingIds in same chat`() {
        val chatId = 7L
        val a = registry.tryStartQuickExport(UUID.randomUUID(), chatId, ExportMode.ORIGINAL, UUID.randomUUID(), newJob())
        val b = registry.tryStartQuickExport(UUID.randomUUID(), chatId, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
        assertIs<ActiveExportRegistry.StartResult.Success>(a)
        assertIs<ActiveExportRegistry.StartResult.Success>(b)
    }

    @Test
    fun `tryStartDialogExport returns Success and stores entry`() {
        val exportId = UUID.randomUUID()
        val chatId = 77L
        val job = newJob()

        val r = registry.tryStartDialogExport(exportId, chatId, ExportMode.ORIGINAL, job)

        assertIs<ActiveExportRegistry.StartResult.Success>(r)
        val entry = registry.get(exportId)
        assertNotNull(entry)
        assertNull(entry.recordingId)
    }

    @Test
    fun `tryStartDialogExport returns DuplicateChat for same chatId`() {
        val chatId = 99L
        registry.tryStartDialogExport(UUID.randomUUID(), chatId, ExportMode.ORIGINAL, newJob())
        val second = registry.tryStartDialogExport(UUID.randomUUID(), chatId, ExportMode.ANNOTATED, newJob())
        assertIs<ActiveExportRegistry.StartResult.DuplicateChat>(second)
    }

    @Test
    fun `QuickExport and DialogExport namespaces are independent`() {
        val chatId = 10L
        val recordingId = UUID.randomUUID()
        val quick = registry.tryStartQuickExport(UUID.randomUUID(), chatId, ExportMode.ANNOTATED, recordingId, newJob())
        val dialog = registry.tryStartDialogExport(UUID.randomUUID(), chatId, ExportMode.ORIGINAL, newJob())
        assertIs<ActiveExportRegistry.StartResult.Success>(quick)
        assertIs<ActiveExportRegistry.StartResult.Success>(dialog)
    }

    @Test
    fun `attachCancellable stores cancellable on entry`() {
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
        val cancellable = CancellableJob { /* noop */ }

        registry.attachCancellable(exportId, cancellable)

        assertSame(cancellable, registry.get(exportId)!!.cancellable)
    }

    @Test
    fun `attachCancellable is a no-op for unknown exportId`() {
        registry.attachCancellable(UUID.randomUUID(), CancellableJob { /* noop */ })
        // No exception, no effect.
    }

    @Test
    fun `markCancelling transitions ACTIVE to CANCELLING and returns entry`() {
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())

        val marked = registry.markCancelling(exportId)

        assertNotNull(marked)
        assertEquals(ActiveExportRegistry.State.CANCELLING, marked.state)
        assertEquals(ActiveExportRegistry.State.CANCELLING, registry.get(exportId)!!.state)
    }

    @Test
    fun `markCancelling returns null on second call`() {
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
        registry.markCancelling(exportId)

        val second = registry.markCancelling(exportId)

        assertNull(second)
    }

    @Test
    fun `markCancelling returns null for unknown exportId`() {
        assertNull(registry.markCancelling(UUID.randomUUID()))
    }

    @Test
    fun `release removes entry from all indexes`() {
        val exportId = UUID.randomUUID()
        val recordingId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, recordingId, newJob())

        registry.release(exportId)

        assertNull(registry.get(exportId))
        val reuse = registry.tryStartQuickExport(UUID.randomUUID(), 1L, ExportMode.ANNOTATED, recordingId, newJob())
        assertIs<ActiveExportRegistry.StartResult.Success>(reuse)
    }

    @Test
    fun `release is idempotent for unknown exportId`() {
        registry.release(UUID.randomUUID())
    }

    @Test
    fun `release of DialogExport frees chatId namespace`() {
        val chatId = 55L
        val exportId = UUID.randomUUID()
        registry.tryStartDialogExport(exportId, chatId, ExportMode.ORIGINAL, newJob())
        registry.release(exportId)
        val reuse = registry.tryStartDialogExport(UUID.randomUUID(), chatId, ExportMode.ORIGINAL, newJob())
        assertIs<ActiveExportRegistry.StartResult.Success>(reuse)
    }

    @Test
    fun `markCancelling after release returns null (TOCTOU)`() {
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
        registry.release(exportId)

        val result = registry.markCancelling(exportId)

        assertNull(result)
    }

    @Test
    fun `attachCancellable fires cancel when entry is already CANCELLING`() = runBlocking {
        // Deterministic sync via CompletableDeferred — `delay(50)` is flaky on slow CI.
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
        registry.markCancelling(exportId)
        val called = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancellable = CancellableJob { called.complete(Unit) }

        registry.attachCancellable(exportId, cancellable)

        // Fail fast if the launch never happens (with a generous 5s ceiling for busy CI).
        kotlinx.coroutines.withTimeout(5_000) { called.await() }
        assertTrue(called.isCompleted, "attachCancellable must invoke cancel when state is already CANCELLING")
        Unit
    }

    @Test
    fun `attachCancellable does not fire cancel when entry is ACTIVE`() = runBlocking {
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
        val called = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancellable = CancellableJob { called.complete(Unit) }

        registry.attachCancellable(exportId, cancellable)

        // A short window is enough — we're asserting the absence of an event. 100ms well exceeds
        // the time needed for an eagerly-dispatched Dispatchers.IO launch to run.
        kotlinx.coroutines.delay(100)
        assertEquals(false, called.isCompleted)
        Unit
    }

    @Test
    fun `release invoked via invokeOnCompletion when LAZY job cancelled before first suspension`() {
        // Guards design §5.3 edge case "LAZY-корутина отменена до первого suspension point":
        // body (with finally { release() }) never runs, so invokeOnCompletion is the only path
        // that cleans the registry. This test mirrors what Quick/Dialog handlers do in production.
        val exportId = UUID.randomUUID()
        val job = kotlinx.coroutines.GlobalScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // Body that would normally run the export.
            kotlinx.coroutines.awaitCancellation()
        }
        try {
            registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), job)
            job.invokeOnCompletion { registry.release(exportId) }
            job.cancel() // cancel BEFORE start() — body never enters
            runBlocking { job.join() }
            assertNull(registry.get(exportId), "registry must be released via invokeOnCompletion")
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `concurrent tryStartQuickExport for same recordingId — exactly one succeeds`() = runBlocking {
        val recordingId = UUID.randomUUID()
        val successes = AtomicInteger(0)
        val duplicates = AtomicInteger(0)
        val starter = CompletableDeferred<Unit>()

        val threads = (1..32).map {
            Thread {
                runBlocking { starter.await() }
                // Create the job locally — do NOT share a `mutableListOf<Job>()` across threads:
                // that's a data race in the test harness itself (iter-2 codex TEST-3). The
                // thread-safe `ConcurrentLinkedQueue` on the class accepts adds safely from newJob().
                val r = registry.tryStartQuickExport(
                    UUID.randomUUID(),
                    1L,
                    ExportMode.ANNOTATED,
                    recordingId,
                    newJob(),
                )
                when (r) {
                    is ActiveExportRegistry.StartResult.Success -> successes.incrementAndGet()
                    is ActiveExportRegistry.StartResult.DuplicateRecording -> duplicates.incrementAndGet()
                    else -> {}
                }
            }
        }
        threads.forEach { it.start() }
        starter.complete(Unit)
        threads.forEach { it.join() }

        assertEquals(1, successes.get())
        assertEquals(31, duplicates.get())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

`./gradlew :telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.export.ActiveExportRegistryTest"` (build-runner).
Expected: all tests fail with "class ActiveExportRegistry not found".

- [ ] **Step 3: Implement `ActiveExportRegistry`**

Create `ActiveExportRegistry.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import kotlinx.coroutines.Job
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of currently active video exports.
 *
 * Primary key is a synthetic `exportId` (UUID) generated by the caller. Secondary dedup indexes:
 * - `byRecordingId` — guards QuickExport duplicates per recording.
 * - `byChat` — guards /export duplicates per chat.
 *
 * Namespaces are independent: a QuickExport for some recording does not block a /export in the
 * same chat.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ActiveExportRegistry(
    private val exportScope: ExportCoroutineScope,
) {
    enum class State { ACTIVE, CANCELLING }

    data class Entry(
        val exportId: UUID,
        val chatId: Long,
        val mode: ExportMode,
        val recordingId: UUID?,
        val job: Job,
        @Volatile var cancellable: CancellableJob? = null,
        @Volatile var state: State = State.ACTIVE,
    )

    sealed class StartResult {
        data class Success(val exportId: UUID) : StartResult()
        data object DuplicateRecording : StartResult()
        data object DuplicateChat : StartResult()
    }

    private val byExportId = ConcurrentHashMap<UUID, Entry>()
    private val byRecordingId = ConcurrentHashMap<UUID, UUID>()
    private val byChat = ConcurrentHashMap<Long, UUID>()
    private val startLock = Any()

    fun tryStartQuickExport(
        exportId: UUID,
        chatId: Long,
        mode: ExportMode,
        recordingId: UUID,
        job: Job,
    ): StartResult = synchronized(startLock) {
        // Atomic under startLock: putIfAbsent(byRecordingId) + byExportId[...] = Entry so that
        // a failure between the two doesn't leave byRecordingId permanently locked.
        val existing = byRecordingId.putIfAbsent(recordingId, exportId)
        if (existing != null) return StartResult.DuplicateRecording
        byExportId[exportId] = Entry(exportId, chatId, mode, recordingId, job)
        return StartResult.Success(exportId)
    }

    fun tryStartDialogExport(
        exportId: UUID,
        chatId: Long,
        mode: ExportMode,
        job: Job,
    ): StartResult = synchronized(startLock) {
        val existing = byChat.putIfAbsent(chatId, exportId)
        if (existing != null) return StartResult.DuplicateChat
        byExportId[exportId] = Entry(exportId, chatId, mode, null, job)
        return StartResult.Success(exportId)
    }

    /**
     * Publishes the cancel handle. If the entry is already `CANCELLING` at the time of publication
     * (because a cancel click arrived between `submitWithRetry` and this call), fire-and-forget the
     * cancel so the vision server is still told to stop.
     */
    fun attachCancellable(exportId: UUID, cancellable: CancellableJob) {
        val entry = byExportId[exportId] ?: return
        entry.cancellable = cancellable
        if (entry.state == State.CANCELLING) {
            exportScope.launch {
                // Don't use runCatching — it catches Throwable including CancellationException,
                // which would break graceful shutdown propagation on exportScope cancellation.
                try {
                    cancellable.cancel()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // cancelJob itself is tolerant (409/5xx/timeout logged WARN), so this
                    // branch only fires on truly unexpected errors — log and move on.
                    @Suppress("ktlint:standard:no-unused-imports")
                    Unit
                }
            }
        }
    }

    /**
     * Atomic transition `ACTIVE → CANCELLING` under `computeIfPresent` + `synchronized(entry)`.
     * Closes the TOCTOU race vs. `release()` — if a concurrent release removes the entry from the
     * map, `computeIfPresent` sees null and we return null cleanly.
     *
     * @return the entry snapshot after the transition on success, `null` if the entry does not
     *   exist (released) or is already in `CANCELLING`.
     */
    fun markCancelling(exportId: UUID): Entry? {
        var snapshot: Entry? = null
        byExportId.computeIfPresent(exportId) { _, entry ->
            synchronized(entry) {
                if (entry.state != State.CANCELLING) {
                    entry.state = State.CANCELLING
                    snapshot = entry
                }
            }
            entry
        }
        return snapshot
    }

    fun get(exportId: UUID): Entry? = byExportId[exportId]

    /**
     * Idempotent. Order of operations matters:
     *  1. `byExportId.remove(exportId)` — takes the CHM bucket lock briefly. Returns null on
     *     double-call (idempotent).
     *  2. Then `synchronized(entry)` only around secondary-index cleanup.
     *
     * Why this order: if we took `synchronized(entry)` FIRST and then removed from byExportId,
     * we'd acquire (entry monitor → bucket lock). Meanwhile `markCancelling` holds the bucket
     * lock inside `computeIfPresent` and tries to acquire the entry monitor. Two threads →
     * reverse lock order → **deadlock**. By removing from byExportId BEFORE taking the entry
     * monitor, a concurrent `markCancelling` sees the entry absent via `computeIfPresent` and
     * returns null cleanly.
     *
     * Called both from `finally` of the export coroutine and from `Job.invokeOnCompletion` —
     * double-call is safe.
     */
    fun release(exportId: UUID) {
        val entry = byExportId.remove(exportId) ?: return
        synchronized(entry) {
            entry.recordingId?.let { byRecordingId.remove(it, exportId) }
            byChat.remove(entry.chatId, exportId)
        }
    }

    /**
     * Test-only accessor. Returns a snapshot of the primary index. Used by tests in
     * `QuickExportHandlerTest`, `ExportExecutorTest`, and `CancelExportHandlerTest` to locate
     * the active export entry without reflection.
     *
     * `internal` keeps this out of the public API surface of the module.
     */
    internal fun snapshotForTest(): Map<UUID, Entry> = byExportId.toMap()
}
```

**Important**: `ActiveExportRegistry` now takes `ExportCoroutineScope` as a constructor parameter. Task 6 (creating `ExportCoroutineScope`) must already be completed — adjust the task order if you're executing by-hand. Since Task 6 is already bite-sized and independent, running it before Task 5 works; alternatively, temporarily create `ExportCoroutineScope` inline if you prefer strictly monotonic order — but then swap the dependency in Task 6. Recommended: **run Task 6 before Task 5**.

- [ ] **Step 4: Run tests to verify they pass**

`./gradlew :telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.export.ActiveExportRegistryTest"` (build-runner).
Expected: all 14 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistry.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistryTest.kt
git commit -m "feat(telegram): add ActiveExportRegistry with dedup and atomic cancel state"
```

---

### Task 6: `ExportCoroutineScope` bean + i18n keys

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportCoroutineScope.kt`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`

- [ ] **Step 1: Create shared scope bean**

Create `ExportCoroutineScope.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Shared `CoroutineScope` used by `QuickExportHandler`, `ExportExecutor`, and `CancelExportHandler`
 * to launch long-running export coroutines independently of the bot's request-handler scope.
 *
 * Uses `Dispatchers.IO` (not `Default`) because:
 *  - Export coroutines call into blocking ffmpeg via `VideoMergeHelper.process.waitFor(...)`,
 *    which parks a worker thread.
 *  - Cancel fire-and-forget coroutines (`exportScope.launch { cancellable.cancel() }` in
 *    `ActiveExportRegistry.attachCancellable` and `CancelExportHandler`) must reach the vision
 *    server quickly — they can't be starved waiting for Default pool workers held by ffmpeg.
 *  - `IO` is sized for blocking work (default cap 64 threads); `Default` is CPU-bound
 *    (cores count), so it would bottleneck cancel paths under concurrent exports.
 *
 * Graceful shutdown via `@PreDestroy`: any in-flight export coroutines receive a
 * `CancellationException`, their `finally` blocks (temp-file cleanup, registry release) run.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ExportCoroutineScope :
    CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {
    @PreDestroy
    fun shutdown() {
        cancel()
    }
}
```

- [ ] **Step 2: Add i18n keys to `messages_ru.properties`**

Append the following at the end of `modules/telegram/src/main/resources/messages_ru.properties`:

```properties

# Cancellation (common)
cancel.error.format=Ошибка: неверный параметр отмены
cancel.error.not.active=Экспорт уже завершён или недоступен
cancel.error.already.cancelling=Отмена уже выполняется

# QuickExport cancellation
quickexport.button.cancel=\u2716\uFE0F Отмена
quickexport.progress.cancelling=\u23F9\uFE0F Отменяется...
quickexport.cancelled=\u274C Экспорт отменён

# /export cancellation
export.button.cancel=\u2716\uFE0F Отмена
export.progress.cancelling=\u23F9\uFE0F Отменяется...
export.cancelled.by.user=\u274C Экспорт отменён
```

Note: `export.cancelled` is already used by the dialog-cancel path (before export starts) with value "Экспорт отменён." — we add a distinct `export.cancelled.by.user` to avoid collision.

- [ ] **Step 3: Add i18n keys to `messages_en.properties`**

Append at the end of `modules/telegram/src/main/resources/messages_en.properties`:

```properties

# Cancellation (common)
cancel.error.format=Error: invalid cancel parameter
cancel.error.not.active=Export is already finished or unavailable
cancel.error.already.cancelling=Cancellation already in progress

# QuickExport cancellation
quickexport.button.cancel=\u2716\uFE0F Cancel
quickexport.progress.cancelling=\u23F9\uFE0F Cancelling...
quickexport.cancelled=\u274C Export cancelled

# /export cancellation
export.button.cancel=\u2716\uFE0F Cancel
export.progress.cancelling=\u23F9\uFE0F Cancelling...
export.cancelled.by.user=\u274C Export cancelled
```

- [ ] **Step 4: Build check**

Delegate to build-runner: `./gradlew :telegram:compileKotlin`.
Expected: compiles.

- [ ] **Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportCoroutineScope.kt \
        modules/telegram/src/main/resources/messages_ru.properties \
        modules/telegram/src/main/resources/messages_en.properties
git commit -m "feat(telegram): add ExportCoroutineScope bean and i18n keys for cancellation"
```

---

### Task 7: `CancelExportHandler` (new, with tests)

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/cancel/CancelExportHandler.kt`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/cancel/CancelExportHandlerTest.kt`

- [ ] **Step 1: Write failing tests**

Create `CancelExportHandlerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.cancel

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.requests.answers.AnswerCallbackQuery
import dev.inmo.tgbotapi.requests.edit.reply_markup.EditChatMessageReplyMarkup
import dev.inmo.tgbotapi.types.CallbackQueryId
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.Username
import dev.inmo.tgbotapi.types.chat.CommonUser
import dev.inmo.tgbotapi.types.chat.PrivateChatImpl
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ActiveExportRegistry
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ExportCoroutineScope
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import java.util.Locale
import java.util.UUID
import kotlin.test.assertTrue

class CancelExportHandlerTest {
    private val msg = MessageResolver(
        ReloadableResourceBundleMessageSource().apply {
            setBasename("classpath:messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
            setDefaultLocale(Locale.forLanguageTag("en"))
        },
    )
    private val bot: TelegramBot = mockk(relaxed = true)
    private val authFilter: AuthorizationFilter = mockk(relaxed = true)
    private val userService: TelegramUserService = mockk(relaxed = true)
    private val scope = ExportCoroutineScope()
    private val registry = ActiveExportRegistry(scope)

    private val handler = CancelExportHandler(bot, registry, scope, authFilter, userService, msg)

    @Test
    fun `handle on noop prefix answers silently without side effects`() = runTest {
        val chatId = ChatId(RawChatId(111L))
        val msgMock = mockk<ContentMessage<MessageContent>>(relaxed = true).also {
            every { it.chat } returns PrivateChatImpl(chatId, null, null, null, Username("@alice"))
        }
        val cb = mockk<MessageDataCallbackQuery>(relaxed = true).also {
            every { it.data } returns "${CancelExportHandler.NOOP_PREFIX}${UUID.randomUUID()}"
            every { it.id } returns CallbackQueryId("cbq-1")
            every { it.message } returns msgMock
            every { it.user } returns CommonUser(
                id = dev.inmo.tgbotapi.types.UserId(RawChatId(123L)),
                firstName = "Alice",
                username = Username("@alice"),
            )
        }
        coEvery { authFilter.getRole("alice") } returns UserRole.USER

        handler.handle(cb)

        // No registry lookup, no keyboard edit — only ack.
        coVerify { bot.execute(match<AnswerCallbackQuery> { it.callbackQueryId == CallbackQueryId("cbq-1") }) }
        coVerify(exactly = 0) { bot.execute(any<EditChatMessageReplyMarkup>()) }
    }

    @Test
    fun `handle for unknown exportId responds with not-active message`() = runTest {
        val chatId = ChatId(RawChatId(111L))
        val msgMock = mockk<ContentMessage<MessageContent>>(relaxed = true).also {
            every { it.chat } returns PrivateChatImpl(chatId, null, null, null, Username("@alice"))
        }
        val unknown = UUID.randomUUID()
        val cb = mockk<MessageDataCallbackQuery>(relaxed = true).also {
            every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}$unknown"
            every { it.id } returns CallbackQueryId("cbq-2")
            every { it.message } returns msgMock
            every { it.user } returns CommonUser(
                id = dev.inmo.tgbotapi.types.UserId(RawChatId(123L)),
                firstName = "Alice",
                username = Username("@alice"),
            )
        }
        coEvery { authFilter.getRole("alice") } returns UserRole.USER
        coEvery { userService.getUserLanguage(any()) } returns "en"

        handler.handle(cb)

        val answerSlot = slot<AnswerCallbackQuery>()
        coVerify { bot.execute(capture(answerSlot)) }
        assertTrue(
            answerSlot.captured.text!!.contains("already finished or unavailable"),
            "actual: ${answerSlot.captured.text}",
        )
    }

    @Test
    fun `handle for cancel happy path marks registry cancelling, cancels job, calls cancellable`() = runTest {
        val chatId = ChatId(RawChatId(111L))
        val msgMock = mockk<ContentMessage<MessageContent>>(relaxed = true).also {
            every { it.chat } returns PrivateChatImpl(chatId, null, null, null, Username("@alice"))
        }
        val exportId = UUID.randomUUID()
        val recordingId = UUID.randomUUID()
        val job = Job()
        registry.tryStartQuickExport(exportId, 111L, ExportMode.ANNOTATED, recordingId, job)
        var cancellableCalled = false
        registry.attachCancellable(exportId, CancellableJob { cancellableCalled = true })

        val cb = mockk<MessageDataCallbackQuery>(relaxed = true).also {
            every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}$exportId"
            every { it.id } returns CallbackQueryId("cbq-3")
            every { it.message } returns msgMock
            every { it.user } returns CommonUser(
                id = dev.inmo.tgbotapi.types.UserId(RawChatId(123L)),
                firstName = "Alice",
                username = Username("@alice"),
            )
        }
        coEvery { authFilter.getRole("alice") } returns UserRole.USER
        coEvery { userService.getUserLanguage(any()) } returns "en"

        handler.handle(cb)

        // Registry state flipped
        val entry = registry.get(exportId)
        assertTrue(entry != null && entry.state == ActiveExportRegistry.State.CANCELLING)
        // Coroutine Job is cancelled
        assertTrue(job.isCancelled)
        // Give the launched cancellable coroutine a moment to run
        kotlinx.coroutines.delay(50)
        assertTrue(cancellableCalled)
    }

    @Test
    fun `handle for second cancel click returns already-cancelling`() = runTest {
        val chatId = ChatId(RawChatId(111L))
        val msgMock = mockk<ContentMessage<MessageContent>>(relaxed = true).also {
            every { it.chat } returns PrivateChatImpl(chatId, null, null, null, Username("@alice"))
        }
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 111L, ExportMode.ANNOTATED, UUID.randomUUID(), Job())
        registry.markCancelling(exportId)

        val cb = mockk<MessageDataCallbackQuery>(relaxed = true).also {
            every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}$exportId"
            every { it.id } returns CallbackQueryId("cbq-4")
            every { it.message } returns msgMock
            every { it.user } returns CommonUser(
                id = dev.inmo.tgbotapi.types.UserId(RawChatId(123L)),
                firstName = "Alice",
                username = Username("@alice"),
            )
        }
        coEvery { authFilter.getRole("alice") } returns UserRole.USER
        coEvery { userService.getUserLanguage(any()) } returns "en"

        handler.handle(cb)

        val answerSlot = slot<AnswerCallbackQuery>()
        coVerify { bot.execute(capture(answerSlot)) }
        assertTrue(
            answerSlot.captured.text!!.contains("already in progress"),
            "actual: ${answerSlot.captured.text}",
        )
    }

    @Test
    fun `handle for chat mismatch treats as not-active`() = runTest {
        val chatId = ChatId(RawChatId(111L))
        val msgMock = mockk<ContentMessage<MessageContent>>(relaxed = true).also {
            every { it.chat } returns PrivateChatImpl(chatId, null, null, null, Username("@alice"))
        }
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 222L /* DIFFERENT chat */, ExportMode.ANNOTATED, UUID.randomUUID(), Job())

        val cb = mockk<MessageDataCallbackQuery>(relaxed = true).also {
            every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}$exportId"
            every { it.id } returns CallbackQueryId("cbq-5")
            every { it.message } returns msgMock
            every { it.user } returns CommonUser(
                id = dev.inmo.tgbotapi.types.UserId(RawChatId(123L)),
                firstName = "Alice",
                username = Username("@alice"),
            )
        }
        coEvery { authFilter.getRole("alice") } returns UserRole.USER
        coEvery { userService.getUserLanguage(any()) } returns "en"

        handler.handle(cb)

        val answerSlot = slot<AnswerCallbackQuery>()
        coVerify { bot.execute(capture(answerSlot)) }
        assertTrue(
            answerSlot.captured.text!!.contains("already finished or unavailable"),
            "actual: ${answerSlot.captured.text}",
        )
        // Registry state unchanged
        assertTrue(registry.get(exportId)!!.state == ActiveExportRegistry.State.ACTIVE)
    }

    @Test
    fun `handle for unauthorized user responds with unauthorized message`() = runTest {
        val chatId = ChatId(RawChatId(111L))
        val msgMock = mockk<ContentMessage<MessageContent>>(relaxed = true).also {
            every { it.chat } returns PrivateChatImpl(chatId, null, null, null, Username("@bob"))
        }
        val cb = mockk<MessageDataCallbackQuery>(relaxed = true).also {
            every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}${UUID.randomUUID()}"
            every { it.id } returns CallbackQueryId("cbq-6")
            every { it.message } returns msgMock
            every { it.user } returns CommonUser(
                id = dev.inmo.tgbotapi.types.UserId(RawChatId(123L)),
                firstName = "Bob",
                username = Username("@bob"),
            )
        }
        coEvery { authFilter.getRole("bob") } returns null
        coEvery { userService.getUserLanguage(any()) } returns "en"

        handler.handle(cb)

        val answerSlot = slot<AnswerCallbackQuery>()
        coVerify { bot.execute(capture(answerSlot)) }
        assertTrue(
            answerSlot.captured.text!!.contains("not authorized"),
            "actual: ${answerSlot.captured.text}",
        )
    }

    @Test
    fun `handle answers with format error on malformed cancel data`() = runTest {
        // Guards iter-2 codex TEST-2: design §8 requires test for malformed callback data.
        val chatId = ChatId(RawChatId(111L))
        val msgMock = mockk<ContentMessage<MessageContent>>(relaxed = true).also {
            every { it.chat } returns PrivateChatImpl(chatId, null, null, null, Username("@alice"))
        }
        val cb = mockk<MessageDataCallbackQuery>(relaxed = true).also {
            every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}not-a-valid-uuid"
            every { it.id } returns CallbackQueryId("cbq-malformed")
            every { it.message } returns msgMock
            every { it.user } returns CommonUser(
                id = dev.inmo.tgbotapi.types.UserId(RawChatId(123L)),
                firstName = "Alice",
                username = Username("@alice"),
            )
        }
        coEvery { authFilter.getRole("alice") } returns UserRole.USER
        coEvery { userService.getUserLanguage(any()) } returns "en"

        handler.handle(cb)

        val answerSlot = slot<AnswerCallbackQuery>()
        coVerify { bot.execute(capture(answerSlot)) }
        assertTrue(
            answerSlot.captured.text!!.contains("invalid", ignoreCase = true) ||
                answerSlot.captured.text!!.contains("cancel parameter", ignoreCase = true),
            "actual: ${answerSlot.captured.text}",
        )
    }

    @Test
    fun `handle survives editMessageReplyMarkup failure and still cancels the job`() = runTest {
        // Guards iter-2 codex TEST-2: design §8 requires test for Telegram API errors during
        // edit keyboard. `runCatching` in handler must not let the error abort the cancellation.
        val chatId = ChatId(RawChatId(111L))
        val msgMock = mockk<ContentMessage<MessageContent>>(relaxed = true).also {
            every { it.chat } returns PrivateChatImpl(chatId, null, null, null, Username("@alice"))
        }
        val exportId = UUID.randomUUID()
        val job = Job()
        registry.tryStartQuickExport(exportId, 111L, ExportMode.ANNOTATED, UUID.randomUUID(), job)
        val cb = mockk<MessageDataCallbackQuery>(relaxed = true).also {
            every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}$exportId"
            every { it.id } returns CallbackQueryId("cbq-edit-fail")
            every { it.message } returns msgMock
            every { it.user } returns CommonUser(
                id = dev.inmo.tgbotapi.types.UserId(RawChatId(123L)),
                firstName = "Alice",
                username = Username("@alice"),
            )
        }
        coEvery { authFilter.getRole("alice") } returns UserRole.USER
        coEvery { userService.getUserLanguage(any()) } returns "en"
        // Simulate the edit keyboard call failing (e.g. Telegram API error / message deleted).
        coEvery { bot.execute(any<EditChatMessageReplyMarkup>()) } throws RuntimeException("api down")

        handler.handle(cb)

        // Despite the edit failure, registry state MUST flip and the job MUST be cancelled.
        val entry = registry.get(exportId)
        assertTrue(entry != null && entry.state == ActiveExportRegistry.State.CANCELLING)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `parseExportId returns UUID for valid cancel data`() {
        val id = UUID.randomUUID()
        val parsed = CancelExportHandler.parseExportId("${CancelExportHandler.CANCEL_PREFIX}$id")
        assertTrue(parsed == id)
    }

    @Test
    fun `parseExportId returns UUID for valid noop data`() {
        val id = UUID.randomUUID()
        val parsed = CancelExportHandler.parseExportId("${CancelExportHandler.NOOP_PREFIX}$id")
        assertTrue(parsed == id)
    }

    @Test
    fun `parseExportId returns null for invalid UUID`() {
        val parsed = CancelExportHandler.parseExportId("${CancelExportHandler.CANCEL_PREFIX}not-a-uuid")
        assertTrue(parsed == null)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

`./gradlew :telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.cancel.CancelExportHandlerTest"` (build-runner).
Expected: all fail with "class CancelExportHandler not found".

- [ ] **Step 3: Implement `CancelExportHandler`**

Create `CancelExportHandler.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.cancel

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.StartCommandHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ActiveExportRegistry
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ExportCoroutineScope
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Handles two callback prefixes:
 *  - `xc:{exportId}` — real cancellation: marks registry entry as CANCELLING, edits keyboard to
 *    "Cancelling...", cancels the export coroutine (`Job.cancel()`), and fire-and-forgets
 *    `CancellableJob.cancel()` to tell the vision server to stop processing.
 *  - `np:{exportId}` — silent no-op for the progress button; just answers the callback query
 *    so Telegram stops showing a loading spinner on the user's device.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class CancelExportHandler(
    private val bot: TelegramBot,
    private val registry: ActiveExportRegistry,
    private val exportScope: ExportCoroutineScope,
    private val authFilter: AuthorizationFilter,
    private val userService: TelegramUserService,
    private val msg: MessageResolver,
) {
    suspend fun handle(callback: DataCallbackQuery) {
        val data = callback.data

        if (data.startsWith(NOOP_PREFIX)) {
            answerSafely(callback)
            return
        }

        if (!data.startsWith(CANCEL_PREFIX)) {
            logger.warn { "CancelExportHandler received unexpected callback data: $data" }
            answerSafely(callback)
            return
        }

        val messageCallback = callback as? MessageDataCallbackQuery
        val message = messageCallback?.message
        if (message == null) {
            answerSafely(callback)
            return
        }

        val chatId = message.chat.id
        val user = callback.user
        val username = user.username?.withoutAt
        val lang = resolveLang(chatId.chatId.long, user.ietfLanguageCode?.code)

        if (username == null || authFilter.getRole(username) == null) {
            answerSafely(callback, msg.get("common.error.unauthorized", lang))
            return
        }

        val exportId = parseExportId(data)
        if (exportId == null) {
            answerSafely(callback, msg.get("cancel.error.format", lang))
            return
        }

        val entry = registry.get(exportId)
        if (entry == null || entry.chatId != chatId.chatId.long) {
            answerSafely(callback, msg.get("cancel.error.not.active", lang))
            return
        }

        val marked = registry.markCancelling(exportId)
        if (marked == null) {
            answerSafely(callback, msg.get("cancel.error.already.cancelling", lang))
            return
        }

        answerSafely(callback)
        logger.info {
            "Export cancelled by user exportId=$exportId chatId=${marked.chatId} mode=${marked.mode} " +
                "recordingId=${marked.recordingId}"
        }

        try {
            bot.editMessageReplyMarkup(
                message,
                replyMarkup = buildCancellingKeyboard(exportId, marked.recordingId, lang),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to update keyboard to cancelling state" }
        }

        marked.job.cancel(CancellationException("user cancelled"))

        marked.cancellable?.let { cancellable ->
            exportScope.launch {
                try {
                    cancellable.cancel()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Vision server cancel failed exportId=$exportId" }
                }
            }
        }
    }

    private fun buildCancellingKeyboard(
        exportId: UUID,
        recordingId: UUID?,
        lang: String,
    ): InlineKeyboardMarkup {
        // Distinguish QuickExport vs /export by presence of recordingId (NOT by ExportMode — that
        // enum has only ANNOTATED/ORIGINAL and both flows use both modes).
        val label =
            if (recordingId != null) {
                msg.get("quickexport.progress.cancelling", lang)
            } else {
                msg.get("export.progress.cancelling", lang)
            }
        return InlineKeyboardMarkup(
            keyboard = matrix {
                row {
                    +CallbackDataInlineKeyboardButton(label, "$NOOP_PREFIX$exportId")
                }
            },
        )
    }

    private suspend fun resolveLang(chatId: Long, ietf: String?): String =
        try {
            userService.getUserLanguage(chatId) ?: StartCommandHandler.detectLanguage(ietf)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StartCommandHandler.detectLanguage(ietf)
        }

    /**
     * Helper: answer the callback, swallowing only non-cancellation errors. We can't use
     * `runCatching` here because it catches `Throwable` including `CancellationException`,
     * which would break graceful shutdown propagation (iter-2 gemini BUG-2).
     */
    private suspend fun answerSafely(callback: DataCallbackQuery, text: String? = null) {
        try {
            if (text != null) bot.answer(callback, text) else bot.answer(callback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to answer callback query" }
        }
    }

    companion object {
        const val CANCEL_PREFIX = "xc:"
        const val NOOP_PREFIX = "np:"

        fun parseExportId(data: String): UUID? {
            // Strict: require exactly one of the prefixes at the start. `data = "xc:np:uuid"` must
            // not parse — removePrefix-chain would silently accept it.
            val raw = when {
                data.startsWith(CANCEL_PREFIX) -> data.removePrefix(CANCEL_PREFIX)
                data.startsWith(NOOP_PREFIX) -> data.removePrefix(NOOP_PREFIX)
                else -> return null
            }
            return try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

`./gradlew :telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.cancel.CancelExportHandlerTest"` (build-runner).
Expected: all 9 tests PASS. If `CommonUser` constructor signature differs in the project's ktgbotapi version, adjust the mock-user builder calls in tests to match (grep `CommonUser(` in existing tests for the exact signature used elsewhere).

- [ ] **Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/cancel/CancelExportHandler.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/cancel/CancelExportHandlerTest.kt
git commit -m "feat(telegram): add CancelExportHandler for xc: and np: callbacks"
```

---

### Task 8: `FrigateAnalyzerBot` — route `xc:` and `np:` callbacks

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`

- [ ] **Step 1: Add dependency and routing**

Edit `FrigateAnalyzerBot.kt`. Add import:

```kotlin
import ru.zinin.frigate.analyzer.telegram.bot.handler.cancel.CancelExportHandler
```

Add constructor parameter (insert after `quickExportHandler`):

```kotlin
    private val quickExportHandler: QuickExportHandler,
    private val cancelExportHandler: CancelExportHandler,
    private val msg: MessageResolver,
```

Inside `registerRoutes()`, directly after the existing `onDataCallbackQuery` block for `qe:/qea:`, add:

```kotlin
        onDataCallbackQuery(
            initialFilter = {
                it.data.startsWith(CancelExportHandler.CANCEL_PREFIX) ||
                    it.data.startsWith(CancelExportHandler.NOOP_PREFIX)
            },
        ) { callback ->
            try {
                cancelExportHandler.handle(callback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error handling cancel/noop callback: ${callback.data}" }
            }
        }
```

- [ ] **Step 2: Build check**

Delegate to build-runner: `./gradlew :telegram:compileKotlin :core:compileKotlin`.
Expected: compiles.

- [ ] **Step 3: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt
git commit -m "feat(telegram): route xc: and np: callbacks to CancelExportHandler"
```

---

### Task 9: Migrate `QuickExportHandler` to registry + cancel UI

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt`

- [ ] **Step 1: Refactor `QuickExportHandler`**

Replace `QuickExportHandler.kt` entirely (keep existing imports, add new ones):

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.media.sendVideo
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import ru.zinin.frigate.analyzer.telegram.bot.handler.StartCommandHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.cancel.CancelExportHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ActiveExportRegistry
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ExportCoroutineScope
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class QuickExportHandler(
    private val bot: TelegramBot,
    private val videoExportService: VideoExportService,
    private val authorizationFilter: AuthorizationFilter,
    private val properties: TelegramProperties,
    private val msg: MessageResolver,
    private val userService: TelegramUserService,
    private val registry: ActiveExportRegistry,
    private val exportScope: ExportCoroutineScope,
) {
    @Suppress("LongMethod")
    suspend fun handle(callback: DataCallbackQuery): Job? {
        val messageCallback = callback as? MessageDataCallbackQuery
        val message = messageCallback?.message
        if (message == null) {
            bot.answer(callback)
            return null
        }
        val chatId = message.chat.id
        val chatIdLong = chatId.chatId.long

        val callbackData = callback.data
        val mode =
            if (callbackData.startsWith(CALLBACK_PREFIX_ANNOTATED)) ExportMode.ANNOTATED else ExportMode.ORIGINAL

        val recordingId = parseRecordingId(callbackData)
        if (recordingId == null) {
            val lang =
                try {
                    userService.getUserLanguage(chatIdLong)
                        ?: StartCommandHandler.detectLanguage(callback.user.ietfLanguageCode?.code)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    StartCommandHandler.detectLanguage(callback.user.ietfLanguageCode?.code)
                }
            bot.answer(callback, msg.get("quickexport.error.format", lang))
            return null
        }

        val user = callback.user
        val username = user.username?.withoutAt
        if (username == null) {
            val lang = StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
            bot.answer(callback, msg.get("quickexport.error.username", lang))
            return null
        }

        if (authorizationFilter.getRole(username) == null) {
            val lang = StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
            bot.answer(callback, msg.get("common.error.unauthorized", lang))
            return null
        }

        val lang =
            try {
                userService.getUserLanguage(chatIdLong)
                    ?: StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
            }

        val exportId = UUID.randomUUID()
        val job = exportScope.launch(start = CoroutineStart.LAZY) {
            runExport(message, chatIdLong, chatId, recordingId, mode, lang, exportId)
        }
        // Safety net for LAZY cancel-before-body — finally in runExport won't fire if job is
        // cancelled before its first suspension point. release() is idempotent, so double-firing
        // (finally + invokeOnCompletion) is harmless.
        job.invokeOnCompletion { registry.release(exportId) }

        val startResult = registry.tryStartQuickExport(exportId, chatIdLong, mode, recordingId, job)
        when (startResult) {
            is ActiveExportRegistry.StartResult.DuplicateRecording -> {
                job.cancel()
                bot.answer(callback, msg.get("quickexport.error.concurrent", lang))
                return null
            }
            is ActiveExportRegistry.StartResult.DuplicateChat -> {
                // Impossible for QuickExport (registry doesn't check byChat for QuickExport).
                // If this ever fires, it's a programming error — fail loudly.
                job.cancel()
                error("Unexpected DuplicateChat from tryStartQuickExport")
            }
            is ActiveExportRegistry.StartResult.Success -> {
                bot.answer(callback)
                try {
                    bot.editMessageReplyMarkup(
                        message,
                        replyMarkup =
                            createProgressKeyboard(
                                exportId,
                                recordingId,
                                msg.get("quickexport.button.processing", lang),
                                msg.get("quickexport.button.cancel", lang),
                            ),
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update button to processing state" }
                }
                job.start()
                return job
            }
        }
    }

    @Suppress("LongMethod")
    private suspend fun runExport(
        message: ContentMessage<*>,
        chatIdLong: Long,
        chatId: dev.inmo.tgbotapi.types.IdChatIdentifier,
        recordingId: UUID,
        mode: ExportMode,
        lang: String,
        exportId: UUID,
    ) {
        try {
            val timeout =
                if (mode == ExportMode.ANNOTATED) {
                    QUICK_EXPORT_ANNOTATED_TIMEOUT_MS
                } else {
                    QUICK_EXPORT_ORIGINAL_TIMEOUT_MS
                }

            var lastRenderedStage: VideoExportProgress.Stage? = null
            var lastRenderedPercent: Int? = null

            val onProgress: suspend (VideoExportProgress) -> Unit = { progress ->
                if (registry.get(exportId)?.state == ActiveExportRegistry.State.CANCELLING) {
                    // Skip — keyboard is showing "Cancelling..." now.
                    return@onProgress
                }
                val shouldUpdate =
                    when {
                        progress.stage != lastRenderedStage -> true
                        progress.stage == VideoExportProgress.Stage.ANNOTATING && progress.percent != null -> {
                            val lastPct = lastRenderedPercent ?: -1
                            (progress.percent - lastPct) >= 5
                        }
                        else -> false
                    }

                if (shouldUpdate) {
                    lastRenderedStage = progress.stage
                    lastRenderedPercent = progress.percent
                    val text = renderProgressButton(progress.stage, progress.percent, lang)
                    try {
                        bot.editMessageReplyMarkup(
                            message,
                            replyMarkup =
                                createProgressKeyboard(
                                    exportId,
                                    recordingId,
                                    text,
                                    msg.get("quickexport.button.cancel", lang),
                                ),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to update progress button" }
                    }
                }
            }

            val onJobSubmitted: suspend (ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob) -> Unit =
                { cancellable -> registry.attachCancellable(exportId, cancellable) }

            val videoPath =
                withTimeoutOrNull(timeout) {
                    videoExportService.exportByRecordingId(
                        recordingId = recordingId,
                        mode = mode,
                        onProgress = onProgress,
                        onJobSubmitted = onJobSubmitted,
                    )
                }

            if (videoPath == null) {
                logger.warn {
                    "Quick export outer timeout fired (recordingId=$recordingId, mode=$mode, timeoutMs=$timeout)."
                }
                bot.sendTextMessage(chatId, msg.get("quickexport.error.timeout", lang))
                restoreButton(message, recordingId, lang)
                return
            }

            try {
                try {
                    bot.editMessageReplyMarkup(
                        message,
                        replyMarkup =
                            createProgressKeyboard(
                                exportId,
                                recordingId,
                                msg.get("quickexport.progress.sending", lang),
                                msg.get("quickexport.button.cancel", lang),
                            ),
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update progress button to sending" }
                }

                val sent =
                    withTimeoutOrNull(properties.sendVideoTimeout.toMillis()) {
                        bot.sendVideo(
                            chatId,
                            videoPath.toFile().asMultipartFile().copy(filename = "quick_export_$recordingId.mp4"),
                        )
                    }

                if (sent == null) {
                    bot.sendTextMessage(chatId, msg.get("quickexport.error.send.timeout", lang))
                }
            } finally {
                // cleanupExportFile is suspend → any suspend call in an already-cancelled coroutine
                // instantly throws CancellationException without doing real work. Wrap in
                // NonCancellable so the temp-file is actually deleted on user-cancel/shutdown.
                withContext(NonCancellable) {
                    try {
                        videoExportService.cleanupExportFile(videoPath)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to cleanup: $videoPath" }
                    }
                }
            }

            restoreButton(message, recordingId, lang)
        } catch (e: CancellationException) {
            val entry = registry.get(exportId)
            if (entry?.state == ActiveExportRegistry.State.CANCELLING) {
                // UI updates are suspend (bot.sendTextMessage / bot.editMessageReplyMarkup inside
                // restoreButton). In a cancelled coroutine they throw CancellationException on
                // first suspension, leaving the user stuck on "⏹ Отменяется…". Wrap in
                // NonCancellable so the final "❌ Отменён" actually reaches Telegram.
                withContext(NonCancellable) {
                    try {
                        bot.sendTextMessage(chatId, msg.get("quickexport.cancelled", lang))
                    } catch (ex: Exception) {
                        logger.warn(ex) { "Failed to send cancelled message" }
                    }
                    restoreButton(message, recordingId, lang)
                }
                return
            }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Quick export failed for recording $recordingId" }
            val errorMsg =
                when (e) {
                    is IllegalArgumentException -> msg.get("quickexport.error.not.found", lang)
                    is IllegalStateException -> msg.get("quickexport.error.unavailable", lang)
                    is DetectTimeoutException -> msg.get("quickexport.error.annotation.timeout", lang)
                    else -> msg.get("quickexport.error.generic", lang)
                }
            try {
                bot.sendTextMessage(chatId, errorMsg)
            } catch (ex: Exception) {
                logger.warn(ex) { "Failed to send error message" }
            }
            restoreButton(message, recordingId, lang)
        } finally {
            registry.release(exportId)
        }
    }

    private fun renderProgressButton(
        stage: VideoExportProgress.Stage,
        percent: Int? = null,
        lang: String,
    ): String =
        when (stage) {
            VideoExportProgress.Stage.PREPARING -> msg.get("quickexport.progress.preparing", lang)
            VideoExportProgress.Stage.MERGING -> msg.get("quickexport.progress.merging", lang)
            VideoExportProgress.Stage.COMPRESSING -> msg.get("quickexport.progress.compressing", lang)
            VideoExportProgress.Stage.ANNOTATING -> {
                if (percent != null) {
                    msg.get("quickexport.progress.annotating.percent", lang, percent)
                } else {
                    msg.get("quickexport.progress.annotating", lang)
                }
            }
            VideoExportProgress.Stage.SENDING -> msg.get("quickexport.progress.sending", lang)
            VideoExportProgress.Stage.DONE -> msg.get("quickexport.progress.done", lang)
        }

    fun createExportKeyboard(
        recordingId: UUID,
        lang: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            keyboard =
                matrix {
                    row {
                        +CallbackDataInlineKeyboardButton(
                            msg.get("quickexport.button.original", lang),
                            "$CALLBACK_PREFIX$recordingId",
                        )
                        +CallbackDataInlineKeyboardButton(
                            msg.get("quickexport.button.annotated", lang),
                            "$CALLBACK_PREFIX_ANNOTATED$recordingId",
                        )
                    }
                },
        )

    private suspend fun restoreButton(
        message: ContentMessage<*>?,
        recordingId: UUID,
        lang: String,
    ) {
        if (message == null) return
        try {
            bot.editMessageReplyMarkup(
                message,
                replyMarkup = createExportKeyboard(recordingId, lang),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to restore button" }
        }
    }

    companion object {
        const val CALLBACK_PREFIX = "qe:"
        const val CALLBACK_PREFIX_ANNOTATED = "qea:"
        private const val QUICK_EXPORT_ORIGINAL_TIMEOUT_MS = 300_000L
        private const val QUICK_EXPORT_ANNOTATED_TIMEOUT_MS = 3_000_000L

        internal fun parseRecordingId(callbackData: String): UUID? {
            val recordingIdStr =
                callbackData
                    .removePrefix(CALLBACK_PREFIX_ANNOTATED)
                    .removePrefix(CALLBACK_PREFIX)
            return try {
                UUID.fromString(recordingIdStr)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * Two-row keyboard: progress-button (row 1, `np:` noop callback) + cancel-button (row 2, `xc:` cancel).
         */
        fun createProgressKeyboard(
            exportId: UUID,
            @Suppress("unused") recordingId: UUID,
            progressText: String,
            cancelText: String,
        ): InlineKeyboardMarkup =
            InlineKeyboardMarkup(
                keyboard =
                    matrix {
                        row {
                            +CallbackDataInlineKeyboardButton(
                                progressText,
                                "${CancelExportHandler.NOOP_PREFIX}$exportId",
                            )
                        }
                        row {
                            +CallbackDataInlineKeyboardButton(
                                cancelText,
                                "${CancelExportHandler.CANCEL_PREFIX}$exportId",
                            )
                        }
                    },
            )
    }
}
```

- [ ] **Step 2: Update existing `QuickExportHandlerTest` to new constructor + 5-arg mocks**

Open `QuickExportHandlerTest.kt`. At the top-level class, find every construction of `QuickExportHandler(...)` and add two parameters (`registry`, `exportScope`). Also update all `coEvery`/`coVerify` for `exportByRecordingId` to match 5 positional `any()` arguments.

Find and replace (class-level, not inside a single test):

```kotlin
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ActiveExportRegistry
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ExportCoroutineScope
```

Wherever the test instantiates the handler (search `QuickExportHandler(`), add last two args:

```kotlin
val exportScope = ExportCoroutineScope()
val registry = ActiveExportRegistry(exportScope)   // Ctor requires ExportCoroutineScope — see Task 5 Step 3
val handler = QuickExportHandler(
    bot = bot,
    videoExportService = videoExportService,
    authorizationFilter = authFilter,
    properties = properties,
    msg = msg,
    userService = userService,
    registry = registry,
    exportScope = exportScope,
)
```

Ensure `@AfterEach { exportScope.shutdown() }` is added (or the existing tearDown calls it) — the scope owns a `SupervisorJob`, not stopping it leaks coroutines across tests.

Update every mock expectation:

```kotlin
coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } returns tempFile
```

to:

```kotlin
coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any(), any()) } returns tempFile
```

Similarly for `coVerify`. Do this for every occurrence.

- [ ] **Step 3: Add mandatory cancellation tests in `QuickExportHandlerTest`**

Add a new `@Nested inner class CancellationTest` (after existing nested classes). **Mandatory** (not optional) — these tests guard the most critical new behavior:

```kotlin
    @Nested
    inner class CancellationTest {
        @Test
        fun `progress keyboard has two rows — progress noop + cancel`() {
            val exportId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val kb = QuickExportHandler.createProgressKeyboard(exportId, recordingId, "progress", "cancel")
            assertEquals(2, kb.keyboard.size, "progress keyboard must have 2 rows")
            val row1 = kb.keyboard[0]
            val row2 = kb.keyboard[1]
            assertEquals(1, row1.size)
            assertEquals(1, row2.size)
            val noop = row1[0] as CallbackDataInlineKeyboardButton
            val cancel = row2[0] as CallbackDataInlineKeyboardButton
            assertTrue(noop.callbackData.startsWith("np:"), "row 1 must be np:-callback")
            assertTrue(cancel.callbackData.startsWith("xc:"), "row 2 must be xc:-callback")
            assertTrue(noop.callbackData.endsWith(exportId.toString()))
            assertTrue(cancel.callbackData.endsWith(exportId.toString()))
        }

        @Test
        fun `when user cancels — registry released and cancelled message sent`() = runTest {
            // Setup: same as existing "successful export" test, but videoExportService.exportByRecordingId
            // should suspend indefinitely (awaitCancellation) so we can cancel it externally.
            val bot: TelegramBot = mockk(relaxed = true)
            val videoExportService: VideoExportService = mockk(relaxed = true)
            val authFilter: AuthorizationFilter = mockk(relaxed = true)
            val userService: TelegramUserService = mockk(relaxed = true)
            val properties = mockk<TelegramProperties>(relaxed = true).also {
                every { it.sendVideoTimeout } returns Duration.ofSeconds(30)
            }
            val scope = ExportCoroutineScope()
            val registry = ActiveExportRegistry(scope)
            val handler = QuickExportHandler(
                bot, videoExportService, authFilter, properties, msg, userService, registry, scope,
            )
            coEvery { authFilter.getRole(any()) } returns UserRole.USER
            coEvery { userService.getUserLanguage(any()) } returns "en"
            coEvery {
                videoExportService.exportByRecordingId(any(), any(), any(), any(), any())
            } coAnswers { kotlinx.coroutines.awaitCancellation() }
            val recordingId = UUID.randomUUID()
            val callback = makeQuickExportCallback(recordingId)
            val returnedJob = handler.handle(callback)!!

            // Locate the exportId via the test-only accessor added to the registry in Task 5 Step 3.
            // The registry is keyed by exportId, so iterate the snapshot to find the one with our
            // recordingId. (The older draft had `registry.get(recordingId)` — that method doesn't
            // exist; get() takes exportId only.)
            val exportId = registry.snapshotForTest().values.first { it.recordingId == recordingId }.exportId
            registry.markCancelling(exportId)
            returnedJob.cancel()
            returnedJob.join()

            // Verify: registry released; "cancelled" text sent.
            assertNull(registry.get(exportId))
            coVerify {
                bot.sendTextMessage(
                    any<dev.inmo.tgbotapi.types.IdChatIdentifier>(),
                    match<String> { it.contains("cancelled", ignoreCase = true) or it.contains("Отменён") },
                )
            }
            scope.shutdown()
        }

        // Helper: see Helpers at the bottom of the test file.
    }
```

Implementor note:
- Uses `ActiveExportRegistry.snapshotForTest()` defined in Task 5 Step 3 — it returns a map of all active exports keyed by exportId, so tests can locate entries by any field (recordingId, chatId, etc.) without reflection.
- Both sub-tests are **mandatory** — iter-1 TEST-2 upgraded them from optional. The first (keyboard shape) is a pure unit test; the second (end-to-end cancel flow) exercises the `invokeOnCompletion { release }` safety net and the `withContext(NonCancellable)` UI branch together.

- [ ] **Step 4: Run full telegram test suite**

`./gradlew :telegram:test` (build-runner).
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt
git commit -m "feat(quickexport): use ActiveExportRegistry and add cancel button"
```

---

### Task 10: Migrate `ExportExecutor` + `ExportCommandHandler`

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportCommandHandler.kt`

- [ ] **Step 1: Rewrite `ExportExecutor`**

Replace `ExportExecutor.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.media.sendVideo
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.cancel.CancelExportHandler
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress.Stage
import java.time.ZoneId
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ExportExecutor(
    private val bot: TelegramBot,
    private val videoExportService: VideoExportService,
    private val properties: TelegramProperties,
    private val msg: MessageResolver,
    private val registry: ActiveExportRegistry,
    private val exportScope: ExportCoroutineScope,
) {
    @Suppress("LongMethod")
    suspend fun execute(
        chatId: IdChatIdentifier,
        userZone: ZoneId,
        dialogResult: ExportDialogOutcome.Success,
        lang: String,
    ) {
        val (startInstant, endInstant, camId, mode) = dialogResult

        val exportId = UUID.randomUUID()
        val job: Job = exportScope.launch(start = CoroutineStart.LAZY) {
            runExport(chatId, userZone, camId, mode, startInstant, endInstant, lang, exportId)
        }
        // Safety net for LAZY cancel-before-body. Idempotent with finally inside runExport.
        job.invokeOnCompletion { registry.release(exportId) }

        val startResult =
            registry.tryStartDialogExport(exportId, chatId.chatId.long, mode, job)
        when (startResult) {
            is ActiveExportRegistry.StartResult.DuplicateChat -> {
                job.cancel()
                bot.sendTextMessage(chatId, msg.get("export.error.concurrent", lang))
                return
            }
            is ActiveExportRegistry.StartResult.DuplicateRecording -> {
                // Impossible for /export (registry doesn't check byRecordingId for /export).
                // Programming error — fail loudly.
                job.cancel()
                error("Unexpected DuplicateRecording from tryStartDialogExport")
            }
            is ActiveExportRegistry.StartResult.Success -> {
                job.start()
                job.join()
            }
        }
    }

    @Suppress("LongMethod")
    private suspend fun runExport(
        chatId: IdChatIdentifier,
        userZone: ZoneId,
        camId: String,
        mode: ExportMode,
        startInstant: java.time.Instant,
        endInstant: java.time.Instant,
        lang: String,
        exportId: UUID,
    ) {
        val cancelKeyboard = cancelKeyboard(exportId, lang)
        val statusMessage =
            bot.sendTextMessage(
                chatId,
                renderProgress(Stage.PREPARING, mode = mode, msg = msg, lang = lang),
                replyMarkup = cancelKeyboard,
            )

        var lastRenderedStage: Stage? = Stage.PREPARING
        var lastRenderedPercent: Int? = null
        var hadCompressing = false

        val onProgress: suspend (VideoExportProgress) -> Unit = { progress ->
            if (registry.get(exportId)?.state == ActiveExportRegistry.State.CANCELLING) {
                return@onProgress
            }
            if (progress.stage == Stage.COMPRESSING) hadCompressing = true

            val shouldUpdate =
                when {
                    progress.stage != lastRenderedStage -> true
                    progress.stage == Stage.ANNOTATING && progress.percent != null -> {
                        val lastPct = lastRenderedPercent ?: -1
                        (progress.percent - lastPct) >= 5
                    }
                    else -> false
                }

            if (shouldUpdate) {
                lastRenderedStage = progress.stage
                lastRenderedPercent = progress.percent
                try {
                    bot.editMessageText(
                        statusMessage,
                        renderProgress(progress.stage, progress.percent, mode, hadCompressing, msg, lang),
                        replyMarkup = cancelKeyboard,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }
            }
        }

        val onJobSubmitted: suspend (CancellableJob) -> Unit = { cancellable ->
            registry.attachCancellable(exportId, cancellable)
        }

        try {
            val processingTimeout =
                if (mode == ExportMode.ANNOTATED) EXPORT_ANNOTATED_TIMEOUT_MS else EXPORT_ORIGINAL_TIMEOUT_MS
            val videoPath =
                withTimeoutOrNull(processingTimeout) {
                    videoExportService.exportVideo(
                        startInstant = startInstant,
                        endInstant = endInstant,
                        camId = camId,
                        mode = mode,
                        onProgress = onProgress,
                        onJobSubmitted = onJobSubmitted,
                    )
                } ?: run {
                    // Drop the cancel keyboard on the status message — otherwise a dead "✖ Отмена"
                    // button remains on the final error screen.
                    runCatching {
                        bot.editMessageText(
                            statusMessage,
                            msg.get("export.error.processing.timeout", lang),
                            replyMarkup = null,
                        )
                    }.onFailure {
                        bot.sendTextMessage(chatId, msg.get("export.error.processing.timeout", lang))
                    }
                    return
                }

            try {
                try {
                    bot.editMessageText(
                        statusMessage,
                        renderProgress(Stage.SENDING, mode = mode, compressing = hadCompressing, msg = msg, lang = lang),
                        replyMarkup = cancelKeyboard,
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }

                val localDate = startInstant.atZone(userZone).toLocalDate()
                val localStart = startInstant.atZone(userZone).toLocalTime()
                val localEnd = endInstant.atZone(userZone).toLocalTime()
                val fileName = "export_${camId}_${localDate}_$localStart-$localEnd.mp4".replace(":", "-")
                val sent =
                    withTimeoutOrNull(properties.sendVideoTimeout.toMillis()) {
                        bot.sendVideo(
                            chatId,
                            videoPath.toFile().asMultipartFile().copy(filename = fileName),
                        )
                    }

                if (sent == null) {
                    logger.error {
                        "Telegram sendVideo timed out after ${properties.sendVideoTimeout} " +
                            "for chat=$chatId, camera=$camId, file=$fileName"
                    }
                    // Drop keyboard on final error.
                    runCatching {
                        bot.editMessageText(
                            statusMessage,
                            msg.get("export.error.send.timeout", lang, properties.sendVideoTimeout.toSeconds()),
                            replyMarkup = null,
                        )
                    }.onFailure {
                        bot.sendTextMessage(
                            chatId,
                            msg.get("export.error.send.timeout", lang, properties.sendVideoTimeout.toSeconds()),
                        )
                    }
                    return
                }

                try {
                    bot.editMessageText(
                        statusMessage,
                        renderProgress(Stage.DONE, mode = mode, compressing = hadCompressing, msg = msg, lang = lang),
                        replyMarkup = null,
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }
            } finally {
                // cleanupExportFile is suspend → instantly rethrows CancellationException in a
                // cancelled coroutine without doing the actual IO. Must run under NonCancellable
                // so the temp file is really deleted on user-cancel/shutdown.
                withContext(NonCancellable) {
                    try {
                        videoExportService.cleanupExportFile(videoPath)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to delete temp file: $videoPath" }
                    }
                }
            }
        } catch (e: CancellationException) {
            val entry = registry.get(exportId)
            if (entry?.state == ActiveExportRegistry.State.CANCELLING) {
                // bot.editMessageText is suspend; in a cancelled coroutine the final "❌ Отменён"
                // would never reach Telegram. NonCancellable wraps the UI update so the user sees
                // the final state instead of getting stuck on "⏹ Отменяется…".
                withContext(NonCancellable) {
                    try {
                        bot.editMessageText(
                            statusMessage,
                            msg.get("export.cancelled.by.user", lang),
                            replyMarkup = null,
                        )
                    } catch (ex: Exception) {
                        logger.warn(ex) { "Failed to render cancelled state" }
                    }
                }
                return
            }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Video export failed" }
            val errorText =
                if (mode == ExportMode.ANNOTATED) {
                    msg.get("export.error.annotated", lang)
                } else {
                    msg.get("export.error.original", lang)
                }
            try {
                bot.editMessageText(statusMessage, errorText, replyMarkup = null)
            } catch (ex: Exception) {
                bot.sendTextMessage(chatId, errorText)
            }
        } finally {
            registry.release(exportId)
        }
    }

    private fun cancelKeyboard(exportId: UUID, lang: String): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            keyboard = matrix {
                row {
                    +CallbackDataInlineKeyboardButton(
                        msg.get("export.button.cancel", lang),
                        "${CancelExportHandler.CANCEL_PREFIX}$exportId",
                    )
                }
            },
        )
}
```

- [ ] **Step 2: Update `ExportCommandHandler` — keep `ActiveExportTracker` for dialog phase**

Do **NOT** remove `ActiveExportTracker`. It guards the dialog phase where registry has no hook (dialog runs before any `exportId` is generated, and ktgbotapi's `waitDataCallbackQuery`/`waitTextMessage` collectors compete if multiple dialogs run in the same chat).

Refactor `ExportCommandHandler.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ExportCommandHandler(
    private val userService: TelegramUserService,
    private val exportDialogRunner: ExportDialogRunner,
    private val exportExecutor: ExportExecutor,
    private val activeExportTracker: ActiveExportTracker,
    private val msg: MessageResolver,
) : CommandHandler {
    override val command: String = "export"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 3

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val lang = user?.languageCode ?: "en"
        val chatId = message.chat.id
        val chatIdLong = chatId.chatId.long

        // Dialog-phase lock: prevent two parallel /export dialogs in the same DM from hijacking each
        // other's callback/text replies. Execution-phase lock lives in ExportExecutor via registry.
        if (!activeExportTracker.tryAcquire(chatIdLong)) {
            reply(message, msg.get("export.error.concurrent", lang))
            return
        }
        try {
            val userZone = userService.getUserZone(chatIdLong)
            val outcome = with(exportDialogRunner) { runDialog(chatId, userZone, lang) }

            when (outcome) {
                is ExportDialogOutcome.Success -> {
                    exportExecutor.execute(chatId, userZone, outcome, lang)
                }
                is ExportDialogOutcome.Cancelled -> {
                }
                is ExportDialogOutcome.Timeout -> {
                    sendTextMessage(chatId, msg.get("export.timeout", lang))
                }
            }
        } finally {
            activeExportTracker.release(chatIdLong)
        }
    }
}
```

Notes on lock layering:
- `ActiveExportTracker` = dialog-phase chat lock (original role, preserved).
- `ActiveExportRegistry.tryStartDialogExport(...)` = execution-phase chat lock + exportId registry (new).
- Both locks are held concurrently for a short period (during `exportExecutor.execute`), but they don't deadlock — registry's `synchronized(startLock)` is short-lived and doesn't wait on tracker.
- Private `exportScope` in the old handler is removed (executor now manages its own via `ExportCoroutineScope` bean).

- [ ] **Step 3: Write mandatory cancellation test for `ExportExecutor`**

Create/extend `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutorTest.kt`. **Mandatory** (replaces the earlier "optional" flag).

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportExecutorTest {
    private val msg = MessageResolver(
        ReloadableResourceBundleMessageSource().apply {
            setBasename("classpath:messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
            setDefaultLocale(Locale.forLanguageTag("en"))
        },
    )

    @Test
    fun `cancelled export — final editMessageText with null keyboard + registry released`() = runTest {
        val bot: TelegramBot = mockk(relaxed = true)
        val videoExportService: VideoExportService = mockk(relaxed = true)
        val properties = mockk<TelegramProperties>(relaxed = true).also {
            every { it.sendVideoTimeout } returns Duration.ofSeconds(30)
        }
        val scope = ExportCoroutineScope()
        val registry = ActiveExportRegistry(scope)
        val executor = ExportExecutor(bot, videoExportService, properties, msg, registry, scope)

        coEvery {
            videoExportService.exportVideo(any(), any(), any(), any(), any(), any())
        } coAnswers { awaitCancellation() }

        val chatId = dev.inmo.tgbotapi.types.ChatId(dev.inmo.tgbotapi.types.RawChatId(42L))
        val outcome = ExportDialogOutcome.Success(
            Instant.parse("2026-02-16T12:00:00Z"),
            Instant.parse("2026-02-16T12:05:00Z"),
            "cam1",
            ExportMode.ANNOTATED,
        )

        val executeJob = scope.launch { executor.execute(chatId, java.time.ZoneOffset.UTC, outcome, "en") }

        // Wait for registry to have the entry. Use a bounded-timeout loop rather than a fixed
        // `repeat(50)` with `delay(10)` — the loop exits as soon as the entry appears, so fast
        // CI runs aren't blocked, and the 5s ceiling handles slow CI without flakiness.
        val exportId = kotlinx.coroutines.withTimeout(5_000) {
            var id: java.util.UUID? = null
            while (id == null) {
                id = firstActiveExport(registry)
                if (id == null) kotlinx.coroutines.delay(10)
            }
            id
        }

        // Simulate cancel click.
        registry.markCancelling(exportId)
        registry.get(exportId)!!.job.cancel()
        executeJob.join()

        // Final editMessageText should have been called with the cancelled text and null keyboard.
        coVerify {
            bot.editMessageText(any(), match<String> { it.contains("cancelled", ignoreCase = true) or it.contains("Отменён") }, replyMarkup = null)
        }
        // Registry released.
        assertNull(registry.get(exportId))

        scope.shutdown()
    }

    @Test
    fun `second parallel execute — returns DuplicateChat and sends concurrent message`() = runTest {
        // Guards iter-2 ccs/glm BUG-10: the dedup path for /export was previously an empty stub.
        val bot: TelegramBot = mockk(relaxed = true)
        val videoExportService: VideoExportService = mockk(relaxed = true)
        val properties = mockk<TelegramProperties>(relaxed = true).also {
            every { it.sendVideoTimeout } returns Duration.ofSeconds(30)
        }
        val scope = ExportCoroutineScope()
        val registry = ActiveExportRegistry(scope)
        val executor = ExportExecutor(bot, videoExportService, properties, msg, registry, scope)

        // First export suspends forever — holds the chat slot in the registry.
        coEvery {
            videoExportService.exportVideo(any(), any(), any(), any(), any(), any())
        } coAnswers { awaitCancellation() }

        val chatId = dev.inmo.tgbotapi.types.ChatId(dev.inmo.tgbotapi.types.RawChatId(42L))
        val outcome = ExportDialogOutcome.Success(
            Instant.parse("2026-02-16T12:00:00Z"),
            Instant.parse("2026-02-16T12:05:00Z"),
            "cam1",
            ExportMode.ANNOTATED,
        )

        val firstJob = scope.launch { executor.execute(chatId, java.time.ZoneOffset.UTC, outcome, "en") }
        // Wait for first export to register.
        kotlinx.coroutines.withTimeout(2_000) {
            while (registry.snapshotForTest().isEmpty()) kotlinx.coroutines.delay(10)
        }

        // Second execute must see DuplicateChat and send "concurrent" message instead.
        executor.execute(chatId, java.time.ZoneOffset.UTC, outcome, "en")

        coVerify {
            bot.sendTextMessage(
                any<dev.inmo.tgbotapi.types.IdChatIdentifier>(),
                match<String> { it.contains("already running", ignoreCase = true) || it.contains("уже") },
            )
        }

        firstJob.cancel()
        firstJob.join()
        scope.shutdown()
    }

    private fun firstActiveExport(registry: ActiveExportRegistry): java.util.UUID? =
        registry.snapshotForTest().keys.firstOrNull()
}
```

Note: `firstActiveExport` uses `ActiveExportRegistry.snapshotForTest()` which is now defined directly in Task 5 Step 3 (moved there in iter-2 so the test has the accessor available at compile time).

- [ ] **Step 4: Build and run telegram tests**

`./gradlew :telegram:test` (build-runner).
Expected: all tests PASS, including the new cancellation test.

- [ ] **Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportCommandHandler.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistry.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutorTest.kt
git commit -m "feat(export): migrate /export to registry with cancel button"
```

---

### Task 11: ~~Delete `ActiveExportTracker`~~ — SKIPPED

After review iteration 1 (2026-04-17), this task is **removed** from the plan.

`ActiveExportTracker` continues to guard the `/export` dialog phase (`ExportCommandHandler.tryAcquire(chatId)` before `runDialog()`). The registry owns the execution-phase lifecycle only — dialog-phase has no hook into the registry because `exportId` is generated only after the dialog completes.

**No file deletions.** `ActiveExportTrackerTest` is also preserved.

---

### Task 12: Update `.claude/rules` documentation

**Files:**
- Modify: `.claude/rules/telegram.md`
- Modify: `.claude/rules/configuration.md`

- [ ] **Step 1: Add "Cancellation" subsection to `.claude/rules/telegram.md`**

Open `.claude/rules/telegram.md`. Inside the "## Quick Export" section, after the "### Authorization" subsection, append:

```markdown
### Cancellation

Both QuickExport and `/export` support user-initiated cancellation.

| Component | Location | Purpose |
|---|---|---|
| ActiveExportRegistry | `telegram/bot/handler/export/` | Tracks active exports **in execution phase** by synthetic exportId. Dedup: by recordingId for QuickExport, by chatId for /export. |
| ActiveExportTracker | `telegram/bot/handler/export/` | Kept from before. Dialog-phase lock for /export — prevents two parallel /export dialogs in the same DM from hijacking each other's waiter replies. |
| CancelExportHandler | `telegram/bot/handler/cancel/` | Handles `xc:{exportId}` (cancel) and `np:{exportId}` (noop-ack). |
| ExportCoroutineScope | `telegram/bot/handler/export/` | Shared scope for export coroutines, gracefully cancelled on @PreDestroy. |
| CancellableJob (SAM) | `telegram/service/model/` | Hides AcquiredServer behind an abstraction; published via onJobSubmitted callback. |

Cancel callback format: `xc:{exportId}` triggers registry `ACTIVE → CANCELLING` transition. The
coroutine is cancelled (`Job.cancel()`), and if the vision server already has an active annotation
job, `DetectService.cancelJob(server, jobId)` is fire-and-forget-posted to `POST /jobs/{id}/cancel`.

### Lock Ordering Invariant (Tracker + Registry)

`ActiveExportTracker` and `ActiveExportRegistry` both contain short-lived locks. To prevent any
future refactor from introducing a dual-lock deadlock, the following ordering must hold:

1. **Acquire `ActiveExportTracker.tryAcquire(chatId)` BEFORE any `ActiveExportRegistry.*` call.**
2. **Never call `ActiveExportRegistry.*` methods while holding `ActiveExportTracker`'s internal
   lock** (today the tracker's lock scope covers only `tryAcquire`/`release` — keep it that way).
3. Inside `ActiveExportRegistry`: `startLock` is held briefly by `tryStart*`; `synchronized(entry)`
   is held briefly inside `markCancelling`/`release` secondary cleanup. Neither acquires the other.
4. `ActiveExportRegistry.release()` must remove from `byExportId` BEFORE taking `synchronized(entry)`
   — see design §4 for the rationale (avoids reverse-order deadlock with `markCancelling`).

### Known Limitations

- **ffmpeg cancellation is best-effort.** On MERGING/COMPRESSING stages `CancellationException` waits
  for `VideoMergeHelper.process.waitFor(...)` to return before unwinding. UI shows "⏹ Отменяется…"
  immediately but the final "❌ Отменён" appears only after ffmpeg finishes (seconds for merge, up to
  minutes for compress). Full sync cancel would need a cancellation-aware ffmpeg wrapper (out of scope).
- **Restart wipes registry.** Old cancel buttons respond with "Экспорт уже завершён или недоступен".
  Vision-server jobs orphaned on restart are killed by the server's TTL.
- **Legacy Telegram notifications.** Pre-deploy messages with `qe:/qea:` buttons don't have inline
  cancel — start a new export, which opens a fresh status message with the cancel button.
```

- [ ] **Step 2: Add `DETECT_VIDEO_VISUALIZE_CANCEL_TIMEOUT` to `.claude/rules/configuration.md`**

Open `.claude/rules/configuration.md`. Search for `DETECT_VIDEO_VISUALIZE_TIMEOUT` and add a new row right after it:

```markdown
| `DETECT_VIDEO_VISUALIZE_CANCEL_TIMEOUT` | 10s | HTTP timeout for POST /jobs/{id}/cancel on vision server. Tolerant of all errors. |
```

(Place the row inside the same table/format as the existing entry; don't invent a new table.)

- [ ] **Step 3: Commit**

```bash
git add .claude/rules/telegram.md .claude/rules/configuration.md
git commit -m "docs(rules): document export cancellation architecture"
```

---

### Task 13: Full build + ktlint

**Files:** none (verification task)

- [ ] **Step 1: Run ktlint format**

Delegate to build-runner: `./gradlew ktlintFormat`.
Expected: completes without errors. Auto-formats any lingering issues.

- [ ] **Step 2: Run full build**

Delegate to build-runner: `./gradlew build`.
Expected: all compilation passes, all tests pass, ktlint clean.

- [ ] **Step 3: If any ktlint/format changes were applied, commit them**

```bash
git add -u
git diff --cached --quiet || git commit -m "style: apply ktlint formatting"
```

- [ ] **Step 4: Manual e2e smoke (out-of-band, record result in plan worklog)**

Not executable via agent — for the implementor's attention:

1. Start the service locally with a real vision server configured.
2. Trigger a QuickExport in `ANNOTATED` mode on a recording long enough to reach the ANNOTATING stage.
3. While stage indicator shows "Аннотация N%...", click "✖ Отмена".
4. Verify:
   - Keyboard transitions to "⏹ Отменяется...".
   - Within seconds, a new message "❌ Экспорт отменён" appears.
   - Vision server logs show `POST /jobs/{id}/cancel` → 200 with status `processing`; shortly after, the job transitions to `cancelled`.
5. Repeat for `/export` flow.

Record any deviations as follow-up tasks.

- [ ] **Step 5: Remove the plan/spec from the PR diff**

Before opening a PR (per global CLAUDE.md convention):

```bash
git rm docs/superpowers/specs/2026-04-17-export-cancel-design.md \
       docs/superpowers/plans/2026-04-17-export-cancel.md
git commit -m "chore: remove planning docs before PR"
```

The documents remain accessible in branch history if needed.

---

## Self-Review

**1. Spec coverage:**

| Spec section | Covered by task(s) |
|---|---|
| 2.1 ActiveExportRegistry | Task 5 |
| 2.1 CancelExportHandler | Task 7 |
| 2.1 CancellableJob SAM | Task 1 |
| 2.1 ExportCoroutineScope | Task 6 |
| 2.1 DetectService.cancelJob | Task 2 |
| 2.2 Delete ActiveExportTracker | Task 11 — SKIPPED (tracker retained for `/export` dialog-phase lock per iter-1 CRITICAL-1) |
| 2.3 annotateVideo onJobSubmitted + NonCancellable | Task 3 |
| 2.3 VideoExportService plumbing | Task 4 |
| 2.4 FrigateAnalyzerBot routing | Task 8 |
| 3.1 Export launch skeleton | Task 9 (QuickExport), Task 10 (/export) |
| 3.2 Cancel data flow | Task 7 |
| 3.3 UI by phases (QuickExport) | Task 9 |
| 3.3 UI by phases (/export) | Task 10 |
| 4. State Machine (ACTIVE/CANCELLING) | Task 5 (registry CAS), Task 7 (handler), Tasks 9/10 (finally branch) |
| 5.1 DetectService.cancelJob error handling | Task 2 |
| 5.2 Final UI branching on CancellationException | Tasks 9, 10 |
| 5.3 Edge cases | Tasks 5, 7, 9, 10 (various) |
| 6. Configuration (cancelTimeout) | Task 1 |
| 7. i18n keys | Task 6 |
| 8. Testing | Tasks 2, 3, 4, 5, 7, 9 |
| 9. Observability logs | Tasks 2, 7 |
| 10. Docs updates | Task 12 |

All spec sections have tasks.

**2. Placeholder scan:** No "TBD", "TODO", "implement later", or vague steps. Task 9 Step 3 has an optional cancellation-test that is explicitly flagged as flexible (minimum-viable keyboard shape test is acceptable) — this is a pragmatic flexibility, not a placeholder: the task defines what's required vs. desirable.

**3. Type consistency:**
- `CancellableJob` — one declaration site (Task 1), consistent `suspend fun cancel()` across all uses.
- `ActiveExportRegistry.StartResult.Success|DuplicateRecording|DuplicateChat` — used consistently in Tasks 5, 7, 9, 10.
- `ActiveExportRegistry.Entry.state` (`ACTIVE|CANCELLING`) — consistent.
- `CancelExportHandler.CANCEL_PREFIX = "xc:"` and `NOOP_PREFIX = "np:"` — used in Tasks 7, 8, 9, 10, 12.
- `onJobSubmitted: suspend (CancellableJob) -> Unit = {}` — identical signature in `VideoVisualizationService.annotateVideo`, `VideoExportService.exportVideo` / `exportByRecordingId`.

**4. No methods/types referenced without being defined:** all types defined in Tasks 1 and 5 before first use. `ExportCoroutineScope` defined in Task 6 before first use in Task 7. `CancelExportHandler` defined in Task 7 before first use in Task 8.

No gaps found.
