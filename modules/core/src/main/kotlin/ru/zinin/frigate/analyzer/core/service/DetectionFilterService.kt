package ru.zinin.frigate.analyzer.core.service

import ru.zinin.frigate.analyzer.model.response.DetectResponse

interface DetectionFilterService {
    /**
     * Filters detections, keeping only allowed classes.
     * If no objects remain after filtering, returns a response with an empty detections list.
     * @return filtered response
     */
    fun filterDetections(response: DetectResponse): DetectResponse
}
