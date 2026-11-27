package ru.zinin.frigate.analyzer.core.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import ru.zinin.frigate.analyzer.model.response.DetectResponse

private val logger = KotlinLogging.logger {}

@Service
class DetectionPostProcessor(
    private val detectService: DetectService,
    private val detectionFilterService: DetectionFilterService,
    private val detectProperties: DetectProperties,
) {
    /**
     * Кадр, отличающийся от первого кадра записи: сразу good-model, без малой, которая пропускает
     * мелкие далёкие объекты. Если good-model не отвечает, кадр проходит обычный каскад, а не теряется.
     */
    suspend fun processChanged(
        bytes: ByteArray,
        frameIndex: Int,
        consumerId: Int,
    ): DetectResponse {
        val goodModel = detectProperties.goodModel
        logger.debug { "Consumer #$consumerId: frame $frameIndex changed, analyzing with $goodModel" }

        return try {
            detectionFilterService.filterDetections(detectService.detectWithRetry(bytes = bytes, model = goodModel))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) {
                "Consumer #$consumerId: $goodModel failed for changed frame $frameIndex; " +
                    "falling back to ${detectProperties.defaultModel}"
            }
            process(bytes, detectService.detectWithRetry(bytes), frameIndex, consumerId)
        }
    }

    suspend fun process(
        bytes: ByteArray,
        initialResponse: DetectResponse,
        frameIndex: Int,
        consumerId: Int,
    ): DetectResponse {
        val filteredInitial = detectionFilterService.filterDetections(initialResponse)
        if (filteredInitial.detections.isEmpty()) {
            return filteredInitial
        }

        val goodModel = detectProperties.goodModel
        logger.info { "Consumer #$consumerId: Re-checking frame $frameIndex with $goodModel" }

        val recheckResponse =
            try {
                detectService
                    .detectWithRetry(
                        bytes = bytes,
                        model = goodModel,
                    ).also {
                        logger.debug {
                            "Consumer #$consumerId: $goodModel detected ${it.detections.size} objects"
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DetectTimeoutException) {
                logger.warn(e) {
                    "Consumer #$consumerId: $goodModel timed out for frame $frameIndex; " +
                        "falling back to initial results"
                }
                return filteredInitial
            } catch (e: Exception) {
                logger.warn(e) {
                    "Consumer #$consumerId: $goodModel failed for frame $frameIndex; " +
                        "falling back to initial results"
                }
                return filteredInitial
            }

        val filteredRecheck = detectionFilterService.filterDetections(recheckResponse)

        logger.debug {
            "Consumer #$consumerId: $goodModel detected ${filteredRecheck.detections.size} objects (filtered)"
        }

        return filteredRecheck
    }
}
