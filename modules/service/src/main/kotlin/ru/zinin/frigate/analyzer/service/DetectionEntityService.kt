package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.request.CreateDetectionRequest
import java.util.UUID

interface DetectionEntityService {
    suspend fun createDetection(request: CreateDetectionRequest): UUID
}
