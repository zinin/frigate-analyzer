package ru.zinin.frigate.analyzer.core.loadbalancer

import ru.zinin.frigate.analyzer.core.config.properties.DetectServerProperties
import ru.zinin.frigate.analyzer.core.config.properties.RequestConfig
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

data class ServerState(
    val id: String,
    val properties: DetectServerProperties,
    @Volatile var alive: Boolean = false,
    @Volatile var lastCheckTimestamp: Instant = Instant.EPOCH,
    val processingFrameRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingFrameExtractionRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingVisualizeRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingVideoVisualizeRequestsCount: AtomicInteger = AtomicInteger(0),
) {
    fun canAcceptRequest(requestType: RequestType): Boolean = alive && getCurrentCount(requestType) < getMaxCount(requestType)

    private fun getCurrentCount(type: RequestType): Int =
        when (type) {
            RequestType.FRAME -> processingFrameRequestsCount.get()
            RequestType.FRAME_EXTRACTION -> processingFrameExtractionRequestsCount.get()
            RequestType.VISUALIZE -> processingVisualizeRequestsCount.get()
            RequestType.VIDEO_VISUALIZE -> processingVideoVisualizeRequestsCount.get()
        }

    private fun getMaxCount(type: RequestType): Int =
        when (type) {
            RequestType.FRAME -> properties.frameRequests.simultaneousCount
            RequestType.FRAME_EXTRACTION -> properties.framesExtractRequests.simultaneousCount
            RequestType.VISUALIZE -> properties.visualizeRequests.simultaneousCount
            RequestType.VIDEO_VISUALIZE -> properties.videoVisualizeRequests.simultaneousCount
        }
}

fun ServerState.getCounter(type: RequestType): AtomicInteger =
    when (type) {
        RequestType.FRAME -> processingFrameRequestsCount
        RequestType.FRAME_EXTRACTION -> processingFrameExtractionRequestsCount
        RequestType.VISUALIZE -> processingVisualizeRequestsCount
        RequestType.VIDEO_VISUALIZE -> processingVideoVisualizeRequestsCount
    }

fun ServerState.getRequestConfig(type: RequestType): RequestConfig =
    when (type) {
        RequestType.FRAME -> properties.frameRequests
        RequestType.FRAME_EXTRACTION -> properties.framesExtractRequests
        RequestType.VISUALIZE -> properties.visualizeRequests
        RequestType.VIDEO_VISUALIZE -> properties.videoVisualizeRequests
    }
