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
     * Visualizes frames with detections.
     * Selects up to maxFrames frames with the highest confidence, then by number of detections.
     *
     * @param frames list of frames with detection results
     * @param maxFrames maximum number of frames to visualize (default 10)
     * @return list of visualized frames
     * @throws FrameVisualizationException if visualization of at least one frame fails
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
                        // First by maximum confidence among all detections in the frame
                        frame.detectResponse?.detections?.maxOfOrNull { it.confidence } ?: 0.0
                    }.thenByDescending { frame ->
                        // Then by number of detections
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
