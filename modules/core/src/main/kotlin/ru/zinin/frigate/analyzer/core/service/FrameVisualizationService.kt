package ru.zinin.frigate.analyzer.core.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.core.config.properties.LocalVisualizationProperties
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.model.exception.FrameVisualizationException

private val logger = KotlinLogging.logger {}

@Service
class FrameVisualizationService(
    private val localVisualizationService: LocalVisualizationService,
    private val visualizationProperties: LocalVisualizationProperties,
) {
    /**
     * Визуализирует кадры с детекциями.
     * Выбирает до maxFrames кадров с наибольшим confidence, затем по количеству детекций.
     *
     * @param frames список кадров с результатами детекции
     * @param maxFrames максимальное количество кадров для визуализации (по умолчанию 10)
     * @return список визуализированных кадров
     * @throws FrameVisualizationException если визуализация хотя бы одного кадра не удалась
     */
    suspend fun visualizeFrames(
        frames: List<FrameData>,
        maxFrames: Int = visualizationProperties.maxFrames,
    ): List<VisualizedFrameData> {
        val framesWithDetections =
            frames
                .filter { it.detectResponse?.detections?.isNotEmpty() == true }
                .sortedWith(
                    compareByDescending<FrameData> { frame ->
                        // Сначала по максимальному confidence среди всех детекций кадра
                        frame.detectResponse?.detections?.maxOfOrNull { it.confidence } ?: 0.0
                    }.thenByDescending { frame ->
                        // Потом по количеству детекций
                        frame.detectResponse?.detections?.size ?: 0
                    },
                ).take(maxFrames)

        if (framesWithDetections.isEmpty()) {
            logger.debug { "No frames with detections to visualize" }
            return emptyList()
        }

        logger.debug { "Visualizing ${framesWithDetections.size} frames with detections" }

        val failedFrames = mutableListOf<Pair<Int, Exception>>()
        val successfulFrames = mutableListOf<VisualizedFrameData>()

        for (frame in framesWithDetections) {
            try {
                val detectResponse =
                    checkNotNull(frame.detectResponse) {
                        "detectResponse cannot be null for filtered frames"
                    }
                val visualizedBytes =
                    localVisualizationService.visualize(
                        imageBytes = frame.frameBytes,
                        detections = detectResponse.detections,
                    )
                successfulFrames.add(
                    VisualizedFrameData(
                        frameIndex = frame.frameIndex,
                        visualizedBytes = visualizedBytes,
                        detectionsCount = detectResponse.detections.size,
                    ),
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to visualize frame ${frame.frameIndex}" }
                failedFrames.add(frame.frameIndex to e)
            }
        }

        if (failedFrames.isNotEmpty()) {
            val failedIndices = failedFrames.joinToString(", ") { it.first.toString() }
            throw FrameVisualizationException(
                "Failed to visualize ${failedFrames.size} frame(s): [$failedIndices]",
                failedFrames.first().second,
            )
        }

        return successfulFrames
    }
}
