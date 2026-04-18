package ru.zinin.frigate.analyzer.core.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToFlux
import org.springframework.web.reactive.function.client.bodyToMono
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.loadbalancer.AcquiredServer
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.loadbalancer.RequestType
import ru.zinin.frigate.analyzer.model.exception.DetectServerUnavailableException
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import ru.zinin.frigate.analyzer.model.exception.UnprocessableVideoException
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.FrameExtractionResponse
import ru.zinin.frigate.analyzer.model.response.JobCreatedResponse
import ru.zinin.frigate.analyzer.model.response.JobStatusResponse
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

@Service
class DetectService(
    private val webClient: WebClient,
    private val detectServerLoadBalancer: DetectServerLoadBalancer,
    private val detectProperties: DetectProperties,
    private val tempFileHelper: TempFileHelper,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Performs detection with automatic retry on any errors.
     * Retries continue until success or timeout expiration.
     *
     * @param timeoutMs timeout in milliseconds (for testing)
     * @throws DetectTimeoutException if the wait timeout is exceeded
     */
    suspend fun detectWithRetry(
        bytes: ByteArray,
        conf: Double = detectProperties.defaultConfidence,
        imgSize: Int = detectProperties.defaultImgSize,
        model: String = detectProperties.defaultModel,
        timeoutMs: Long = detectProperties.frameTimeout.toMillis(),
    ): DetectResponse =
        retryWithTimeout(timeoutMs, "Detection") {
            detect(bytes, conf, imgSize, model)
        }

    /**
     * Performs a single detection attempt on an available server.
     *
     * @throws DetectServerUnavailableException if no servers are available
     * @throws Exception on any server error
     */
    private suspend fun detect(
        bytes: ByteArray,
        conf: Double,
        imgSize: Int,
        model: String,
    ): DetectResponse {
        val multipartData =
            MultipartBodyBuilder()
                .apply {
                    part("file", ByteArrayResource(bytes))
                        .contentType(MediaType.IMAGE_JPEG)
                        .filename("file.jpg")
                }.build()

        val acquired = detectServerLoadBalancer.acquireServer(RequestType.FRAME)

        try {
            return webClient
                .post()
                .uri { uriBuilder ->
                    uriBuilder
                        .scheme(acquired.schema)
                        .host(acquired.host)
                        .port(acquired.port)
                        .path("/detect")
                        .queryParam("conf", conf)
                        .queryParam("imgsz", imgSize)
                        .queryParam("model", model)
                        .build()
                }.body(BodyInserters.fromMultipartData(multipartData))
                .retrieve()
                .bodyToMono<DetectResponse>()
                .awaitSingle()
        } catch (e: Exception) {
            logger.warn { "Detection failed on server ${acquired.id}: ${e.message}" }
//            detectServerLoadBalancer.markServerDead(acquired.id)
            throw e
        } finally {
            detectServerLoadBalancer.releaseServer(acquired.id, RequestType.FRAME)
        }
    }

    /**
     * Extracts frames from video with automatic retry on any errors.
     * Retries continue until success or timeout expiration.
     *
     * @param bytes video file content
     * @param filePath file path for Content-Disposition
     * @param sceneThreshold scene change sensitivity threshold (lower = more frames)
     * @param minInterval minimum interval between frames in seconds
     * @param maxFrames maximum number of frames
     * @param quality JPEG quality
     * @throws DetectTimeoutException if the wait timeout is exceeded
     */
    suspend fun extractFramesRemoteWithRetry(
        bytes: ByteArray,
        filePath: String,
        recordingId: java.util.UUID,
        sceneThreshold: Double = detectProperties.frameExtraction.sceneThreshold,
        minInterval: Double = detectProperties.frameExtraction.minInterval,
        maxFrames: Int = detectProperties.frameExtraction.maxFrames,
        quality: Int = detectProperties.frameExtraction.quality,
    ): FrameExtractionResponse =
        retryWithTimeout(detectProperties.frameExtractionTimeout.toMillis(), "Frame extraction") {
            extractFramesRemote(bytes, filePath, recordingId, sceneThreshold, minInterval, maxFrames, quality)
        }

    /**
     * Performs a single frame extraction attempt on an available server.
     *
     * @throws DetectServerUnavailableException if no servers are available
     * @throws Exception on any server error
     */
    private suspend fun extractFramesRemote(
        bytes: ByteArray,
        filePath: String,
        recordingId: java.util.UUID,
        sceneThreshold: Double,
        minInterval: Double,
        maxFrames: Int,
        quality: Int,
    ): FrameExtractionResponse {
        val filename = Path.of(filePath).fileName.toString()
        val multipartData =
            MultipartBodyBuilder()
                .apply {
                    part("file", ByteArrayResource(bytes))
                        .contentType(MediaType.parseMediaType("video/mp4"))
                        .filename(filename)
                }.build()

        val acquired = detectServerLoadBalancer.acquireServer(RequestType.FRAME_EXTRACTION)

        try {
            return webClient
                .post()
                .uri { uriBuilder ->
                    uriBuilder
                        .scheme(acquired.schema)
                        .host(acquired.host)
                        .port(acquired.port)
                        .path("/extract/frames")
                        .queryParam("scene_threshold", sceneThreshold)
                        .queryParam("min_interval", minInterval)
                        .queryParam("max_frames", maxFrames)
                        .queryParam("quality", quality)
                        .build()
                }.body(BodyInserters.fromMultipartData(multipartData))
                .retrieve()
                .bodyToMono<FrameExtractionResponse>()
                .awaitSingle()
        } catch (e: WebClientResponseException) {
            logger.warn {
                "Frame extraction failed on server ${acquired.id}: ${e.statusCode} ${e.message} (filePath=$filePath, recordingId=$recordingId)"
            }
            val statusCode = e.statusCode.value()
            if (statusCode in listOf(400, 413, 422)) {
                val detail =
                    try {
                        val body = e.responseBodyAsString
                        val detailNode = objectMapper.readTree(body).path("detail")
                        if (detailNode.isTextual) detailNode.asText() else body
                    } catch (_: Exception) {
                        e.message!!
                    }
                throw UnprocessableVideoException(detail, e)
            }
            throw e
        } catch (e: Exception) {
            logger.warn { "Frame extraction failed on server ${acquired.id}: ${e.message} (filePath=$filePath, recordingId=$recordingId)" }
            throw e
        } finally {
            detectServerLoadBalancer.releaseServer(acquired.id, RequestType.FRAME_EXTRACTION)
        }
    }

    /**
     * Performs detection with visualization and automatic retry on any errors.
     * Retries continue until success or timeout expiration.
     *
     * @param bytes image content
     * @param conf confidence threshold (0.0 - 1.0)
     * @param imgSize image size for inference
     * @param maxDet maximum number of detections
     * @param lineWidth bounding box line width
     * @param showLabels whether to show class labels
     * @param showConf whether to show confidence values
     * @param quality JPEG quality (1-100)
     * @return ByteArray with JPEG image
     * @throws DetectTimeoutException if the wait timeout is exceeded
     */
    suspend fun detectVisualizeWithRetry(
        bytes: ByteArray,
        conf: Double = detectProperties.defaultConfidence,
        imgSize: Int = detectProperties.defaultImgSize,
        maxDet: Int = detectProperties.visualize.maxDet,
        lineWidth: Int = detectProperties.visualize.lineWidth,
        showLabels: Boolean = detectProperties.visualize.showLabels,
        showConf: Boolean = detectProperties.visualize.showConf,
        quality: Int = detectProperties.visualize.quality,
        model: String = detectProperties.defaultModel,
    ): ByteArray =
        retryWithTimeout(detectProperties.visualizeTimeout.toMillis(), "Visualization") {
            detectVisualize(bytes, conf, imgSize, maxDet, lineWidth, showLabels, showConf, quality, model)
        }

    /**
     * Performs a single detection with visualization attempt on an available server.
     *
     * @throws DetectServerUnavailableException if no servers are available
     * @throws Exception on any server error
     * @return ByteArray with JPEG image
     */
    private suspend fun detectVisualize(
        bytes: ByteArray,
        conf: Double,
        imgSize: Int,
        maxDet: Int,
        lineWidth: Int,
        showLabels: Boolean,
        showConf: Boolean,
        quality: Int,
        model: String,
    ): ByteArray {
        val multipartData =
            MultipartBodyBuilder()
                .apply {
                    part("file", ByteArrayResource(bytes))
                        .contentType(MediaType.IMAGE_JPEG)
                        .filename("file.jpg")
                }.build()

        val acquired = detectServerLoadBalancer.acquireServer(RequestType.VISUALIZE)

        try {
            return webClient
                .post()
                .uri { uriBuilder ->
                    uriBuilder
                        .scheme(acquired.schema)
                        .host(acquired.host)
                        .port(acquired.port)
                        .path("/detect/visualize")
                        .queryParam("conf", conf)
                        .queryParam("imgsz", imgSize)
                        .queryParam("max_det", maxDet)
                        .queryParam("line_width", lineWidth)
                        .queryParam("show_labels", showLabels)
                        .queryParam("show_conf", showConf)
                        .queryParam("quality", quality)
                        .queryParam("model", model)
                        .build()
                }.body(BodyInserters.fromMultipartData(multipartData))
                .retrieve()
                .bodyToMono<ByteArray>()
                .awaitSingle()
        } catch (e: Exception) {
            logger.warn { "Visualization failed on server ${acquired.id}: ${e.message}" }
            throw e
        } finally {
            detectServerLoadBalancer.releaseServer(acquired.id, RequestType.VISUALIZE)
        }
    }

    /**
     * Submits video for annotation to a specific server.
     * Does NOT manage slots — the caller is responsible for acquire/release.
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
                    }.queryParam("line_width", lineWidth)
                    .queryParam("show_labels", showLabels)
                    .queryParam("show_conf", showConf)
                    .queryParam("model", model)
                    .build()
            }.body(BodyInserters.fromMultipartData(multipartData))
            .retrieve()
            .bodyToMono<JobCreatedResponse>()
            .awaitSingle()
    }

    /**
     * Polls the job status on a specific server.
     * Does not use load balancer — communicates directly with the server running the job.
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

    /**
     * Downloads the annotated video result from a specific server.
     * Uses streaming write to a temporary file to avoid OOM.
     * Does not use load balancer — communicates directly with the server where the job completed.
     *
     * @return Path to the temporary video file. Caller is responsible for deletion.
     */
    suspend fun downloadJobResult(
        acquired: AcquiredServer,
        jobId: String,
    ): Path {
        val tempFile = tempFileHelper.createTempFile("video-annotated-", ".mp4")

        try {
            val flux =
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

            DataBufferUtils
                .write(flux, tempFile, StandardOpenOption.WRITE)
                .then()
                .awaitSingleOrNull()
        } catch (e: CancellationException) {
            safeDeleteTemp(tempFile)
            throw e
        } catch (e: Exception) {
            safeDeleteTemp(tempFile)
            throw e
        }

        return tempFile
    }

    // Wrap deleteIfExists so a filesystem error can't replace the CancellationException / original
    // exception we're about to rethrow. NonCancellable is required because deleteIfExists is
    // suspend and would otherwise throw CE instantly in an already-cancelled coroutine.
    private suspend fun safeDeleteTemp(tempFile: Path) {
        withContext(NonCancellable) {
            try {
                tempFileHelper.deleteIfExists(tempFile)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete temp file: $tempFile" }
            }
        }
    }

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
                // Bodiless: JobStatus enum has no `CANCELLED` variant, so deserializing
                // `{"status":"cancelled"}` into JobStatusResponse would fail. We only care about the
                // HTTP status — a 200 means the server accepted the cancel request, 409 means the
                // job is already terminal. The actual body is irrelevant to clients.
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
                    .toBodilessEntity()
                    .awaitSingleOrNull()
            }
            logger.info { "Cancel request accepted for job $jobId on ${acquired.id}" }
        } catch (e: TimeoutCancellationException) {
            // MUST come before CancellationException catch: TimeoutCancellationException is a subtype,
            // otherwise timeouts would be rethrown as cancellation instead of logged as WARN.
            logger.warn {
                "Cancel request timed out after ${detectProperties.videoVisualize.cancelTimeout} " +
                    "for job $jobId on ${acquired.id}"
            }
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

    /**
     * Generic retry method with timeout.
     * Uses coroutine withTimeout to control execution time.
     *
     * @param timeoutMs timeout in milliseconds
     * @param operationName operation name for logging
     * @param block operation to execute
     * @throws DetectTimeoutException if the timeout is exceeded
     */
    private suspend fun <T> retryWithTimeout(
        timeoutMs: Long,
        operationName: String,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        var lastError: Exception? = null

        try {
            return withTimeout(timeoutMs) {
                while (true) {
                    attempt++

                    try {
                        return@withTimeout block()
                    } catch (e: DetectServerUnavailableException) {
                        lastError = e
                        logRetry(attempt, operationName, "No available servers")
                    } catch (e: UnprocessableVideoException) {
                        throw e // Don't retry on client errors (400/422/413)
                    } catch (e: CancellationException) {
                        throw e // Don't retry on cancellation - maintain structured concurrency
                    } catch (e: Exception) {
                        lastError = e
                        logRetry(attempt, operationName, "Server error: ${e.message}")
                    }

                    delay(detectProperties.retryDelay.toMillis())
                }
                @Suppress("UNREACHABLE_CODE")
                error("Unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            logger.error {
                "$operationName timed out after ${timeoutMs}ms ($attempt attempts). " +
                    "Last error: ${lastError?.message}"
            }
            throw DetectTimeoutException(
                "$operationName timed out after ${timeoutMs}ms ($attempt attempts)",
                lastError,
            )
        }
    }

    private fun logRetry(
        attempt: Int,
        operationName: String,
        reason: String,
    ) {
        if (attempt % 50 == 0) {
            logger.info { "Retrying $operationName... $reason (attempt $attempt)" }
        } else {
            logger.debug { "Retrying $operationName... $reason (attempt $attempt)" }
        }
    }
}
