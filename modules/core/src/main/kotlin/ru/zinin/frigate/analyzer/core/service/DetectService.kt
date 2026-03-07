package ru.zinin.frigate.analyzer.core.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
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
) {
    /**
     * Выполняет детекцию с автоматическим retry при любых ошибках.
     * Retry продолжается до успеха или до истечения таймаута.
     *
     * @param timeoutMs таймаут в миллисекундах (для тестирования)
     * @throws DetectTimeoutException если превышен таймаут ожидания
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
     * Выполняет одну попытку детекции на доступном сервере.
     *
     * @throws DetectServerUnavailableException если нет доступных серверов
     * @throws Exception при любой ошибке от сервера
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
     * Извлекает кадры из видео с автоматическим retry при любых ошибках.
     * Retry продолжается до успеха или до истечения таймаута.
     *
     * @param bytes содержимое видеофайла
     * @param filename имя файла для Content-Disposition
     * @param sceneThreshold порог чувствительности смены сцены (меньше = больше кадров)
     * @param minInterval минимальный интервал между кадрами в секундах
     * @param maxFrames максимальное количество кадров
     * @param quality качество JPEG
     * @throws DetectTimeoutException если превышен таймаут ожидания
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
     * Выполняет одну попытку извлечения кадров на доступном сервере.
     *
     * @throws DetectServerUnavailableException если нет доступных серверов
     * @throws Exception при любой ошибке от сервера
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
                        val detailRegex = """"detail"\s*:\s*"([^"]+)"""".toRegex()
                        detailRegex.find(body)?.groupValues?.get(1) ?: body
                    } catch (_: Exception) {
                        e.message ?: "Unknown client error"
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
     * Выполняет детекцию с визуализацией и автоматическим retry при любых ошибках.
     * Retry продолжается до успеха или до истечения таймаута.
     *
     * @param bytes содержимое изображения
     * @param conf порог уверенности (0.0 - 1.0)
     * @param imgSize размер изображения для инференса
     * @param maxDet максимальное количество детекций
     * @param lineWidth ширина линии bounding box
     * @param showLabels показывать ли классы
     * @param showConf показывать ли confidence
     * @param quality качество JPEG (1-100)
     * @return ByteArray с JPEG изображением
     * @throws DetectTimeoutException если превышен таймаут ожидания
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
     * Выполняет одну попытку детекции с визуализацией на доступном сервере.
     *
     * @throws DetectServerUnavailableException если нет доступных серверов
     * @throws Exception при любой ошибке от сервера
     * @return ByteArray с JPEG изображением
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
     * Отправляет видео на аннотацию на конкретный сервер.
     * НЕ управляет слотами — caller отвечает за acquire/release.
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
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(tempFile)
            throw e
        }

        return tempFile
    }

    /**
     * Универсальный метод retry с таймаутом.
     * Использует корутинный withTimeout для контроля времени выполнения.
     *
     * @param timeoutMs таймаут в миллисекундах
     * @param operationName название операции для логирования
     * @param block операция для выполнения
     * @throws DetectTimeoutException если превышен таймаут
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
