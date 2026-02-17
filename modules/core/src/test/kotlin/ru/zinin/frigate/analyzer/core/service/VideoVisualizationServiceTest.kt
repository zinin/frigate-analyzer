package ru.zinin.frigate.analyzer.core.service

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        val detectService = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper)
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

            val testVideoPath = Files.createTempFile("test-input-", ".mp4")
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

            val testVideoPath = Files.createTempFile("test-input-", ".mp4")
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
            val shortDetectService = DetectService(webClient, shortLoadBalancer, shortDetectProperties, shortTempFileHelper)
            val shortService = VideoVisualizationService(shortDetectService, shortLoadBalancer, shortDetectProperties)

            val testVideoPath = Files.createTempFile("test-input-", ".mp4")
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
            val retryDetectService = DetectService(webClient, retryLoadBalancer, retryDetectProperties, retryTempFileHelper)
            val retryService = VideoVisualizationService(retryDetectService, retryLoadBalancer, retryDetectProperties)

            val testVideoPath = Files.createTempFile("test-input-", ".mp4")
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

    private fun buildWebClient(): WebClient {
        val mapper =
            JsonMapper
                .builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build()

        val strategies =
            ExchangeStrategies
                .builder()
                .codecs { codecs ->
                    codecs.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(mapper))
                    codecs.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(mapper))
                }.build()

        return WebClient.builder().exchangeStrategies(strategies).build()
    }

    private fun applicationProperties(serverProps: DetectServerProperties): ApplicationProperties {
        val dummyPath = Path.of(".")
        val dummyDuration = Duration.ofSeconds(1)

        return ApplicationProperties(
            tempFolder = dummyPath,
            ffmpegPath = dummyPath,
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
