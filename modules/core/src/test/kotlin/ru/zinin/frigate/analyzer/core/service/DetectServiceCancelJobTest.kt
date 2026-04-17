package ru.zinin.frigate.analyzer.core.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import ru.zinin.frigate.analyzer.core.loadbalancer.AcquiredServer
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerRegistry
import ru.zinin.frigate.analyzer.core.loadbalancer.ServerHealthMonitor
import ru.zinin.frigate.analyzer.core.loadbalancer.ServerSelectionStrategy
import ru.zinin.frigate.analyzer.core.testsupport.DetectServiceDispatcher
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.fasterxml.jackson.databind.ObjectMapper as FasterxmlObjectMapper

class DetectServiceCancelJobTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var mockWebServer: MockWebServer
    private lateinit var service: DetectService
    private lateinit var acquired: AcquiredServer

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = DetectServiceDispatcher()
        mockWebServer.start()

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
        val appProps = applicationProperties(serverProps)
        val registry = DetectServerRegistry()
        registry.register("test", serverProps)
        registry.getServer("test")!!.alive = true

        val detectProperties =
            DetectProperties(
                videoVisualize = VideoVisualizeConfig(cancelTimeout = Duration.ofSeconds(2)),
            )
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

        val webClient =
            WebClient
                .builder()
                .exchangeStrategies(
                    ExchangeStrategies
                        .builder()
                        .codecs {
                            it.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(buildJsonMapper()))
                            it.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(buildJsonMapper()))
                        }.build(),
                ).build()

        val loadBalancer =
            DetectServerLoadBalancer(
                appProps,
                registry,
                ServerSelectionStrategy(),
                ServerHealthMonitor(registry, webClient, clock, detectProperties),
            )

        val tempFileHelper = TempFileHelper(appProps, clock)
        tempFileHelper.init()
        service = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper, buildObjectMapper())
        acquired = AcquiredServer(id = "test", properties = serverProps)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.close()
    }

    @Test
    fun `cancelJob sends POST to jobs cancel endpoint and returns normally on 200`() =
        runBlocking {
            service.cancelJob(acquired, "abc-123")
            val request = mockWebServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/jobs/abc-123/cancel", request.url.encodedPath)
        }

    @Test
    fun `cancelJob tolerates 409 (already terminal)`() =
        runBlocking {
            mockWebServer.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse
                            .Builder()
                            .code(409)
                            .addHeader("Content-Type", "application/json")
                            .body("""{"detail":"job already completed"}""")
                            .build()
                }
            service.cancelJob(acquired, "abc-123")
        }

    @Test
    fun `cancelJob tolerates 500`() =
        runBlocking {
            mockWebServer.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse
                            .Builder()
                            .code(500)
                            .addHeader("Content-Type", "application/json")
                            .body("""{"detail":"boom"}""")
                            .build()
                }
            service.cancelJob(acquired, "abc-123")
        }

    @Test
    fun `cancelJob tolerates timeout`() =
        runBlocking {
            val hangCount = AtomicInteger(0)
            mockWebServer.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        hangCount.incrementAndGet()
                        Thread.sleep(5_000)
                        return MockResponse
                            .Builder()
                            .code(200)
                            .body("{}")
                            .build()
                    }
                }
            service.cancelJob(acquired, "abc-123")
        }

    @Test
    fun `cancelJob rethrows parent CancellationException (not TimeoutCancellation)`() =
        runBlocking {
            // Sleep 3s (> cancelTimeout=2s but <= MockWebServer close() 5s grace) to simulate a
            // hanging server while still allowing @AfterEach mockWebServer.close() to reclaim the
            // queue. Test body completes in ~200ms via job.cancel() so the 3s sleep only matters
            // for MockWebServer shutdown cleanup.
            mockWebServer.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        Thread.sleep(3_000)
                        return MockResponse
                            .Builder()
                            .code(200)
                            .body("{}")
                            .build()
                    }
                }
            val scope = CoroutineScope(SupervisorJob())
            val started = CompletableDeferred<Unit>()
            val propagated = CompletableDeferred<Throwable>()
            val job =
                scope.launch {
                    started.complete(Unit)
                    try {
                        service.cancelJob(acquired, "abc-123")
                        propagated.complete(AssertionError("cancelJob returned normally — expected CancellationException"))
                    } catch (e: CancellationException) {
                        propagated.complete(e)
                        throw e
                    }
                }
            started.await()
            delay(100)
            job.cancel()
            job.join()
            val caught = withTimeout(5_000) { propagated.await() }
            assertIs<CancellationException>(caught)
            assertTrue(caught !is TimeoutCancellationException)
            scope.cancel()
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

    private fun buildJsonMapper(): JsonMapper =
        JsonMapper
            .builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build()

    private fun buildObjectMapper(): FasterxmlObjectMapper {
        val builder =
            com.fasterxml.jackson.databind.json.JsonMapper
                .builder()
        builder.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        builder.propertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
        return builder.build()
    }
}
