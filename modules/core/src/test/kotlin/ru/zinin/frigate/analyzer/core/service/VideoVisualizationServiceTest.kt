package ru.zinin.frigate.analyzer.core.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
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
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerRegistry
import ru.zinin.frigate.analyzer.core.loadbalancer.ServerHealthMonitor
import ru.zinin.frigate.analyzer.core.loadbalancer.ServerSelectionStrategy
import ru.zinin.frigate.analyzer.core.testsupport.DetectServiceDispatcher
import ru.zinin.frigate.analyzer.core.testsupport.JobFailedDispatcher
import ru.zinin.frigate.analyzer.core.testsupport.NeverCompletingDispatcher
import ru.zinin.frigate.analyzer.core.testsupport.TransientSubmitFailureDispatcher
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import ru.zinin.frigate.analyzer.model.exception.VideoAnnotationFailedException
import ru.zinin.frigate.analyzer.model.response.JobStatus
import ru.zinin.frigate.analyzer.model.response.JobStatusResponse
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoVisualizationServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var mockWebServer: MockWebServer
    private lateinit var registry: DetectServerRegistry
    private lateinit var webClient: WebClient
    private lateinit var loadBalancer: DetectServerLoadBalancer
    private lateinit var service: VideoVisualizationService

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = DetectServiceDispatcher()
        mockWebServer.start()

        webClient = buildWebClient()
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

        registry = DetectServerRegistry()
        val serverProps =
            DetectServerProperties(
                schema = "http",
                host = "localhost",
                port = mockWebServer.port,
                frameRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
            )
        registry.register("test", serverProps)
        registry.getServer("test")!!.alive = true

        val detectProperties = detectProperties()

        loadBalancer =
            DetectServerLoadBalancer(
                applicationProperties(serverProps),
                registry,
                ServerSelectionStrategy(),
                ServerHealthMonitor(registry, webClient, clock, detectProperties),
            )

        val tempFileHelper = TempFileHelper(applicationProperties(serverProps), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        tempFileHelper.init()
        val detectService = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper, buildObjectMapper())
        service = VideoVisualizationService(detectService, loadBalancer, detectProperties)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.close()
    }

    @Test
    fun `annotateVideo returns video file path and releases server`() =
        runBlocking {
            val progressUpdates = mutableListOf<JobStatusResponse>()

            val testVideoPath = Files.createTempFile(tempDir, "test-input-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))

            try {
                val resultPath =
                    service.annotateVideo(
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
            } finally {
                Files.deleteIfExists(testVideoPath)
            }

            Unit
        }

    @Test
    fun `annotateVideo throws VideoAnnotationFailedException when job fails`() =
        runBlocking {
            mockWebServer.dispatcher = JobFailedDispatcher()

            val testVideoPath = Files.createTempFile(tempDir, "test-input-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))

            try {
                val exception =
                    assertThrows<VideoAnnotationFailedException> {
                        service.annotateVideo(videoPath = testVideoPath)
                    }

                assertTrue(exception.message!!.contains("Out of GPU memory"))

                // Server should be released
                assertEquals(0, registry.getServer("test")!!.processingVideoVisualizeRequestsCount.get())
            } finally {
                Files.deleteIfExists(testVideoPath)
            }

            Unit
        }

    @Test
    fun `annotateVideo throws DetectTimeoutException on timeout`() =
        runBlocking {
            mockWebServer.dispatcher = NeverCompletingDispatcher()

            // Create service with short timeout and poll interval
            val shortDetectProperties =
                DetectProperties(
                    retryDelay = Duration.ofMillis(10),
                    videoVisualize =
                        VideoVisualizeConfig(
                            timeout = Duration.ofMillis(500),
                            pollInterval = Duration.ofMillis(50),
                        ),
                )
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val shortLoadBalancer =
                DetectServerLoadBalancer(
                    applicationProperties(
                        DetectServerProperties(
                            schema = "http",
                            host = "localhost",
                            port = mockWebServer.port,
                            frameRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                            framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                            visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                            videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        ),
                    ),
                    registry,
                    ServerSelectionStrategy(),
                    ServerHealthMonitor(registry, webClient, clock, shortDetectProperties),
                )
            val shortAppProps =
                applicationProperties(
                    DetectServerProperties(
                        schema = "http",
                        host = "localhost",
                        port = mockWebServer.port,
                        frameRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                    ),
                )
            val shortTempFileHelper = TempFileHelper(shortAppProps, clock)
            shortTempFileHelper.init()
            val shortDetectService =
                DetectService(webClient, shortLoadBalancer, shortDetectProperties, shortTempFileHelper, buildObjectMapper())
            val shortService = VideoVisualizationService(shortDetectService, shortLoadBalancer, shortDetectProperties)

            val testVideoPath = Files.createTempFile(tempDir, "test-input-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))

            try {
                assertThrows<DetectTimeoutException> {
                    shortService.annotateVideo(videoPath = testVideoPath)
                }

                // Server should be released
                assertEquals(0, registry.getServer("test")!!.processingVideoVisualizeRequestsCount.get())
            } finally {
                Files.deleteIfExists(testVideoPath)
            }

            Unit
        }

    @Test
    fun `annotateVideo retries submit on transient server error`() =
        runBlocking {
            val dispatcher = TransientSubmitFailureDispatcher(failCount = 1)
            mockWebServer.dispatcher = dispatcher

            // Use short retry delay so test doesn't wait long
            val retryDetectProperties =
                DetectProperties(
                    retryDelay = Duration.ofMillis(10),
                    videoVisualize =
                        VideoVisualizeConfig(
                            timeout = Duration.ofSeconds(10),
                            pollInterval = Duration.ofMillis(100),
                        ),
                )
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val retryLoadBalancer =
                DetectServerLoadBalancer(
                    applicationProperties(
                        DetectServerProperties(
                            schema = "http",
                            host = "localhost",
                            port = mockWebServer.port,
                            frameRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                            framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                            visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                            videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        ),
                    ),
                    registry,
                    ServerSelectionStrategy(),
                    ServerHealthMonitor(registry, webClient, clock, retryDetectProperties),
                )
            val retryAppProps =
                applicationProperties(
                    DetectServerProperties(
                        schema = "http",
                        host = "localhost",
                        port = mockWebServer.port,
                        frameRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                    ),
                )
            val retryTempFileHelper = TempFileHelper(retryAppProps, clock)
            retryTempFileHelper.init()
            val retryDetectService =
                DetectService(webClient, retryLoadBalancer, retryDetectProperties, retryTempFileHelper, buildObjectMapper())
            val retryService = VideoVisualizationService(retryDetectService, retryLoadBalancer, retryDetectProperties)

            val testVideoPath = Files.createTempFile(tempDir, "test-input-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))

            try {
                val resultPath = retryService.annotateVideo(videoPath = testVideoPath)

                // Verify job completed successfully after retry
                assertTrue(Files.exists(resultPath))
                assertTrue(Files.size(resultPath) > 0)

                // Verify retry happened (first attempt failed, second succeeded)
                assertTrue(
                    dispatcher.getSubmitAttempts() >= 2,
                    "Expected at least 2 submit attempts, got ${dispatcher.getSubmitAttempts()}",
                )

                // Server should be released
                assertEquals(0, registry.getServer("test")!!.processingVideoVisualizeRequestsCount.get())

                Files.deleteIfExists(resultPath)
            } finally {
                Files.deleteIfExists(testVideoPath)
            }

            Unit
        }

    @Test
    fun `annotateVideo invokes onJobSubmitted after submit and before first job status poll`() =
        runBlocking {
            // Guards design §2.3 "exactly once right after successful submitWithRetry()":
            // onJobSubmitted must fire AFTER the submit POST returns jobId and BEFORE the first
            // GET /jobs/{id} status poll. Otherwise a user-cancel arriving in that window would
            // miss (server, jobId) publication — orphan job on vision server (iter-4 codex TEST-1).
            //
            // We capture mockWebServer.requestCount inside the callback. The submit POST is
            // request #1. If onJobSubmitted fires strictly between submit and the first poll,
            // the captured count equals 1. If it fires later (after N polls), the count is >1
            // and the test fails — guarding the ordering invariant against regression.
            val testVideoPath = Files.createTempFile(tempDir, "test-input-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))
            val requestCountAtCallback =
                java.util.concurrent.atomic
                    .AtomicInteger(-1)
            val invocations = mutableListOf<String>()
            try {
                val result =
                    service.annotateVideo(
                        videoPath = testVideoPath,
                        onJobSubmitted = { cancellable ->
                            invocations.add(cancellable.toString())
                            requestCountAtCallback.set(mockWebServer.requestCount)
                        },
                    )
                assertEquals(1, invocations.size, "onJobSubmitted must fire exactly once")
                assertEquals(
                    1,
                    requestCountAtCallback.get(),
                    "onJobSubmitted must fire immediately after submit (request #1) and " +
                        "before any status poll — observed request count at callback was " +
                        "${requestCountAtCallback.get()}, total after flow ${mockWebServer.requestCount}",
                )
                Files.deleteIfExists(result)
            } finally {
                Files.deleteIfExists(testVideoPath)
            }
            Unit
        }

    @Test
    fun `annotateVideo invokes onJobSubmitted even when parent coroutine is being cancelled (NonCancellable)`() =
        runBlocking {
            // Guards design §2.3 NonCancellable invariant and iter-2 codex TEST-2: the callback
            // publication must survive a user-cancel that arrives simultaneously with submit
            // completion — otherwise the handler never learns (server, jobId) and can't POST /cancel.
            //
            // Gate pattern (iter-4 codex TEST-2): the callback does (a) signal `entered`, (b) suspend
            // via delay(100), (c) signal `finished`. Externally we wait for `entered`, then cancel
            // the parent, then wait for `finished`. If NonCancellable is removed, cancelling the
            // parent at step (a) would throw CancellationException at step (b) and `finished` would
            // never complete — the finished.await() below would time out and fail the test. The
            // earlier version asserted `callbackFired.isCompleted`, which is set BEFORE suspension
            // and therefore always true regardless of NonCancellable — a false-positive assertion.
            val testVideoPath = Files.createTempFile(tempDir, "test-input-", ".mp4")
            Files.write(testVideoPath, byteArrayOf(1, 2, 3))
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
            try {
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
                val job =
                    scope.launch {
                        try {
                            service.annotateVideo(
                                videoPath = testVideoPath,
                                onJobSubmitted = {
                                    entered.complete(Unit)
                                    // Observation point: parent gets cancelled while we are suspended here.
                                    // Under NonCancellable this delay returns normally; without it, it would
                                    // throw CancellationException and `finished.complete(Unit)` below would
                                    // never run.
                                    kotlinx.coroutines.delay(100)
                                    finished.complete(Unit)
                                },
                            )
                        } catch (_: CancellationException) {
                            // Expected path — we cancelled externally.
                        }
                    }
                kotlinx.coroutines.withTimeout(5_000) { entered.await() }
                job.cancel()
                job.join()
                // The assertion: `finished.complete(Unit)` must have run — proving the full callback
                // body executed despite parent cancellation. withTimeout provides fail-fast on regression.
                kotlinx.coroutines.withTimeout(5_000) { finished.await() }
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
                        mockwebserver3.MockResponse
                            .Builder()
                            .code(500)
                            .body("boom")
                            .build()
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
            val shortAppProps =
                applicationProperties(
                    DetectServerProperties(
                        schema = "http",
                        host = "localhost",
                        port = mockWebServer.port,
                        frameRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                        videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                    ),
                )
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val shortLoadBalancer =
                DetectServerLoadBalancer(
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

    private fun buildWebClient(): WebClient {
        val mapper = buildJsonMapper()

        val strategies =
            ExchangeStrategies
                .builder()
                .codecs { codecs ->
                    codecs.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(mapper))
                    codecs.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(mapper))
                }.build()

        return WebClient.builder().exchangeStrategies(strategies).build()
    }

    private fun buildJsonMapper(): JsonMapper =
        JsonMapper
            .builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build()

    private fun buildObjectMapper(): ObjectMapper {
        val builder =
            com.fasterxml.jackson.databind.json.JsonMapper
                .builder()
        builder.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        builder.propertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
        return builder.build()
    }

    private fun applicationProperties(serverProps: DetectServerProperties): ApplicationProperties {
        val dummyDuration = Duration.ofSeconds(1)

        return ApplicationProperties(
            tempFolder = tempDir,
            ffmpegPath = Path.of("/usr/bin/ffmpeg"),
            connectionTimeout = dummyDuration,
            readTimeout = dummyDuration,
            writeTimeout = dummyDuration,
            responseTimeout = dummyDuration,
            detectServers = mapOf("test" to serverProps),
        )
    }

    private fun detectProperties(): DetectProperties =
        DetectProperties(
            videoVisualize =
                VideoVisualizeConfig(
                    timeout = Duration.ofSeconds(30),
                    pollInterval = Duration.ofMillis(100),
                ),
        )
}
