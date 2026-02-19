package ru.zinin.frigate.analyzer.core.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClientResponseException
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.core.loadbalancer.AcquiredServer
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.loadbalancer.RequestType
import ru.zinin.frigate.analyzer.model.exception.DetectServerUnavailableException
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import ru.zinin.frigate.analyzer.model.exception.VideoAnnotationFailedException
import ru.zinin.frigate.analyzer.model.response.JobCreatedResponse
import ru.zinin.frigate.analyzer.model.response.JobStatus
import ru.zinin.frigate.analyzer.model.response.JobStatusResponse
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

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
            val result =
                withTimeout(timeoutMs) {
                    // Phase 1: Submit with retry (acquireServer + submit, retry on failure)
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

                    // Phase 2: Poll until completed/failed
                    pollUntilDone(server, job.jobId, pollIntervalMs, onProgress)

                    // Phase 3: Download (streaming to temp file)
                    detectService.downloadJobResult(server, job.jobId)
                }
            completed = true
            return result
        } catch (e: TimeoutCancellationException) {
            logger.error { "Video annotation timed out after ${timeoutMs}ms (videoPath=$videoPath, jobId=$jobId)" }
            throw DetectTimeoutException("Video annotation timed out after ${timeoutMs}ms", e)
        } finally {
            acquired?.let { server ->
                loadBalancer.releaseServer(server.id, RequestType.VIDEO_VISUALIZE)
                if (!completed && jobId != null) {
                    logger.warn { "Video annotation did not complete successfully. Job $jobId on server ${server.id} may be orphaned" }
                }
            }
        }
    }

    private suspend fun submitWithRetry(
        videoPath: Path,
        conf: Double,
        imgSize: Int,
        maxDet: Int,
        detectEvery: Int?,
        classes: String?,
        lineWidth: Int,
        showLabels: Boolean,
        showConf: Boolean,
        model: String,
    ): Pair<AcquiredServer, JobCreatedResponse> {
        var attempt = 0

        while (true) {
            attempt++

            val server: AcquiredServer
            try {
                server = loadBalancer.acquireServer(RequestType.VIDEO_VISUALIZE)
            } catch (e: DetectServerUnavailableException) {
                logger.debug { "No servers available for video visualize (attempt $attempt): ${e.message}" }
                delay(detectProperties.retryDelay.toMillis())
                continue
            }

            try {
                val job =
                    detectService.submitVideoVisualize(
                        acquired = server,
                        videoPath = videoPath,
                        conf = conf,
                        imgSize = imgSize,
                        maxDet = maxDet,
                        detectEvery = detectEvery,
                        classes = classes,
                        lineWidth = lineWidth,
                        showLabels = showLabels,
                        showConf = showConf,
                        model = model,
                    )
                return Pair(server, job)
            } catch (e: CancellationException) {
                loadBalancer.releaseServer(server.id, RequestType.VIDEO_VISUALIZE)
                throw e
            } catch (e: WebClientResponseException) {
                loadBalancer.releaseServer(server.id, RequestType.VIDEO_VISUALIZE)
                if (e.statusCode.is4xxClientError) {
                    throw e
                }
                logger.warn { "Submit video visualize failed on server ${server.id} (attempt $attempt): ${e.message}" }
                delay(detectProperties.retryDelay.toMillis())
            } catch (e: Exception) {
                loadBalancer.releaseServer(server.id, RequestType.VIDEO_VISUALIZE)
                logger.warn { "Submit video visualize failed on server ${server.id} (attempt $attempt): ${e.message}" }
                delay(detectProperties.retryDelay.toMillis())
            }
        }
    }

    private suspend fun pollUntilDone(
        server: AcquiredServer,
        jobId: String,
        pollIntervalMs: Long,
        onProgress: suspend (JobStatusResponse) -> Unit,
    ) {
        while (true) {
            delay(pollIntervalMs)

            val status =
                try {
                    detectService.getJobStatus(server, jobId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: WebClientResponseException.NotFound) {
                    throw VideoAnnotationFailedException(
                        "Job $jobId not found on server ${server.id} (server may have restarted)",
                        e,
                    )
                } catch (e: WebClientResponseException) {
                    if (e.statusCode.is4xxClientError) {
                        throw e
                    }
                    logger.warn { "Poll failed for job $jobId: ${e.message}" }
                    continue
                } catch (e: Exception) {
                    logger.warn { "Poll failed for job $jobId: ${e.message}" }
                    continue
                }

            try {
                onProgress(status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "onProgress callback failed for job $jobId" }
            }

            when (status.status) {
                JobStatus.COMPLETED -> {
                    logger.info { "Job $jobId completed. Stats: ${status.stats}" }
                    return
                }

                JobStatus.FAILED -> {
                    throw VideoAnnotationFailedException(
                        "Video annotation job $jobId failed: ${status.error}",
                    )
                }

                else -> {} // QUEUED, PROCESSING -> continue polling
            }
        }
    }
}
