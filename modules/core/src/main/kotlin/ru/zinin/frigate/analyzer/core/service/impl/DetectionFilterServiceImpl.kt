package ru.zinin.frigate.analyzer.core.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.core.config.properties.DetectionFilterProperties
import ru.zinin.frigate.analyzer.core.service.DetectionFilterService
import ru.zinin.frigate.analyzer.model.response.DetectResponse

private val logger = KotlinLogging.logger {}

@Service
class DetectionFilterServiceImpl(
    private val properties: DetectionFilterProperties,
) : DetectionFilterService {
    private val allowedClassesLower: Set<String> by lazy {
        properties.allowedClasses.map { it.trim().lowercase() }.toSet()
    }

    override fun filterDetections(response: DetectResponse): DetectResponse {
        if (!properties.enabled || allowedClassesLower.isEmpty()) {
            return response
        }

        val filteredDetections =
            response.detections.filter { detection ->
                allowedClassesLower.contains(detection.className.lowercase())
            }

        if (response.detections.isNotEmpty() && filteredDetections.isEmpty()) {
            logger.debug {
                "All detections were filtered out. Original classes: " +
                    response.detections.map { it.className }.distinct()
            }
        }

        return response.copy(detections = filteredDetections)
    }
}
