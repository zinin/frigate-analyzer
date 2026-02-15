package ru.zinin.frigate.analyzer.core.service

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
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
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerRegistry
import ru.zinin.frigate.analyzer.core.loadbalancer.ServerHealthMonitor
import ru.zinin.frigate.analyzer.core.loadbalancer.ServerSelectionStrategy
import ru.zinin.frigate.analyzer.core.testsupport.ConfigurableDetectServiceDispatcher
import ru.zinin.frigate.analyzer.core.testsupport.DetectServiceDispatcher
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DetectServiceTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var detectService: DetectService
    private lateinit var registry: DetectServerRegistry
    private lateinit var webClient: WebClient
    private lateinit var loadBalancer: DetectServerLoadBalancer

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

        detectService = DetectService(webClient, loadBalancer, detectProperties)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.close()
    }

    @Test
    fun `detectWithRetry returns response and releases server`() =
        runBlocking {
            val response = detectService.detectWithRetry(byteArrayOf(1, 2, 3))

            assertEquals("yolo26s.pt", response.model)
            assertEquals(1, response.detections.size)
            assertEquals("person", response.detections.first().className)
            assertEquals(0, registry.getServer("test")!!.processingFrameRequestsCount.get())

            val request = mockWebServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/detect", request.url.encodedPath)
            assertTrue(request.url.query!!.contains("conf=0.6"))
            assertTrue(request.url.query!!.contains("imgsz=2016"))
        }

    @Test
    fun `extractFramesRemoteWithRetry returns response and releases server`() =
        runBlocking {
            val response =
                detectService.extractFramesRemoteWithRetry(
                    byteArrayOf(7, 8),
                    filePath = "/test/path/video.mp4",
                    recordingId = java.util.UUID.randomUUID(),
                )

            assertTrue(response.success)
            assertEquals(1, response.framesExtracted)
            assertNotNull(response.frames.firstOrNull())
            assertEquals("ZmFrZV9qcGVn", response.frames.first().imageBase64)
            assertEquals(0, registry.getServer("test")!!.processingFrameExtractionRequestsCount.get())

            val request = mockWebServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/extract/frames", request.url.encodedPath)
            assertTrue(request.url.query!!.contains("scene_threshold=0.05"))
            assertTrue(request.url.query!!.contains("min_interval=1.0"))
            assertTrue(request.url.query!!.contains("max_frames=50"))
            assertTrue(request.url.query!!.contains("quality=85"))
        }

    @Test
    fun `detectVisualizeWithRetry returns jpeg bytes and releases server`() =
        runBlocking {
            val response = detectService.detectVisualizeWithRetry(byteArrayOf(9, 10))

            assertEquals(
                byteArrayOf(
                    0xFF.toByte(),
                    0xD8.toByte(),
                    0xFF.toByte(),
                    0xE0.toByte(),
                    0x00.toByte(),
                    0x10.toByte(),
                    0x4A.toByte(),
                    0x46.toByte(),
                    0x49.toByte(),
                    0x46.toByte(),
                    0x00.toByte(),
                    0x01.toByte(),
                    0x01.toByte(),
                    0x00.toByte(),
                    0x01.toByte(),
                    0x00.toByte(),
                    0x01.toByte(),
                    0x00.toByte(),
                    0x00.toByte(),
                    0x00.toByte(),
                    0xFF.toByte(),
                    0xD9.toByte(),
                ).toList(),
                response.toList(),
            )
            assertEquals(0, registry.getServer("test")!!.processingVisualizeRequestsCount.get())

            val request = mockWebServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/detect/visualize", request.url.encodedPath)
            assertTrue(request.url.query!!.contains("conf=0.6"))
            assertTrue(request.url.query!!.contains("imgsz=2016"))
            assertTrue(request.url.query!!.contains("max_det=100"))
            assertTrue(request.url.query!!.contains("line_width=2"))
            assertTrue(request.url.query!!.contains("show_labels=true"))
            assertTrue(request.url.query!!.contains("show_conf=true"))
            assertTrue(request.url.query!!.contains("quality=90"))
        }

    // ==================== Timeout Tests ====================

    @Test
    fun `detectWithRetry throws DetectTimeoutException after timeout`() =
        runBlocking {
            mockWebServer.dispatcher = ConfigurableDetectServiceDispatcher(initialFailureCount = 1000)
            val server = registry.getServer("test")!!
            server.alive = true

            assertFailsWith<DetectTimeoutException> {
                detectService.detectWithRetry(byteArrayOf(1, 2, 3), timeoutMs = 100)
            }

            // Server should be released even after timeout exception
            assertEquals(0, server.processingFrameRequestsCount.get())
        }

    // ==================== Retry Behavior Tests ====================

    @Test
    fun `detectWithRetry retries after transient failure and releases server`() =
        runBlocking {
            mockWebServer.dispatcher = ConfigurableDetectServiceDispatcher(initialFailureCount = 2)
            val server = registry.getServer("test")!!
            server.alive = true

            val response = detectService.detectWithRetry(byteArrayOf(1, 2, 3))

            assertEquals("yolo26s.pt", response.model)
            assertEquals(1, response.detections.size)
            assertEquals(0, server.processingFrameRequestsCount.get())

            // Should have made 3 requests: 2 failures + 1 success
            assertEquals(3, (mockWebServer.dispatcher as ConfigurableDetectServiceDispatcher).getRequestCount())
        }

    @Test
    fun `detectWithRetry releases server even on exception`() =
        runBlocking {
            mockWebServer.dispatcher = ConfigurableDetectServiceDispatcher(initialFailureCount = 1000)
            val server = registry.getServer("test")!!
            server.alive = true

            assertFailsWith<DetectTimeoutException> {
                detectService.detectWithRetry(byteArrayOf(1, 2, 3), timeoutMs = 100)
            }

            // Server should be released even after timeout exception
            assertEquals(0, server.processingFrameRequestsCount.get())
        }

    // ==================== Multi-Server Tests ====================

    @Test
    fun `detectWithRetry uses priority-based server selection`() =
        runBlocking {
            val primaryServer = registry.getServer("test")!!
            primaryServer.alive = true

            // Add secondary server with lower priority (higher priority number)
            val secondaryProps =
                DetectServerProperties(
                    schema = "http",
                    host = "localhost",
                    port = mockWebServer.port,
                    frameRequests = RequestConfig(simultaneousCount = 1, priority = 10),
                    framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 10),
                    visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 10),
                )
            registry.register("secondary", secondaryProps)
            registry.getServer("secondary")!!.alive = true

            val response = detectService.detectWithRetry(byteArrayOf(1, 2, 3))

            assertEquals("yolo26s.pt", response.model)
            // Primary server (priority 0) should be used
            assertEquals(0, primaryServer.processingFrameRequestsCount.get())
            assertEquals(0, registry.getServer("secondary")!!.processingFrameRequestsCount.get())
        }

    @Test
    fun `detectWithRetry fails over to secondary server when primary at capacity`() =
        runBlocking {
            val primaryServer = registry.getServer("test")!!
            primaryServer.alive = true
            // Set primary server to full capacity
            primaryServer.processingFrameRequestsCount.set(1)

            // Add secondary server
            val secondaryProps =
                DetectServerProperties(
                    schema = "http",
                    host = "localhost",
                    port = mockWebServer.port,
                    frameRequests = RequestConfig(simultaneousCount = 1, priority = 10),
                    framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 10),
                    visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 10),
                )
            registry.register("secondary", secondaryProps)
            val secondaryServer = registry.getServer("secondary")!!
            secondaryServer.alive = true

            val response = detectService.detectWithRetry(byteArrayOf(1, 2, 3))

            assertEquals("yolo26s.pt", response.model)
            // Secondary server should be used
            assertEquals(1, primaryServer.processingFrameRequestsCount.get()) // Still at capacity
            assertEquals(0, secondaryServer.processingFrameRequestsCount.get()) // Released
        }

    // ==================== Edge Case Tests ====================

    @Test
    fun `detectWithRetry with custom confidence and imgSize sends correct params`() =
        runBlocking {
            detectService.detectWithRetry(byteArrayOf(1, 2, 3), conf = 0.8, imgSize = 4032)

            val request = mockWebServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/detect", request.url.encodedPath)
            assertTrue(request.url.query!!.contains("conf=0.8"))
            assertTrue(request.url.query!!.contains("imgsz=4032"))
        }

    @Test
    fun `extractFramesRemoteWithRetry with custom parameters sends correct params`() =
        runBlocking {
            detectService.extractFramesRemoteWithRetry(
                byteArrayOf(7, 8),
                filePath = "/custom/path/video.mp4",
                recordingId = java.util.UUID.randomUUID(),
                sceneThreshold = 0.1,
                minInterval = 2.0,
                maxFrames = 100,
                quality = 95,
            )

            val request = mockWebServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/extract/frames", request.url.encodedPath)
            assertTrue(request.url.query!!.contains("scene_threshold=0.1"))
            assertTrue(request.url.query!!.contains("min_interval=2.0"))
            assertTrue(request.url.query!!.contains("max_frames=100"))
            assertTrue(request.url.query!!.contains("quality=95"))
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

    private fun detectProperties(): DetectProperties = DetectProperties()
}
