package ru.zinin.frigate.analyzer.service.helper

import ru.zinin.frigate.analyzer.model.dto.RepresentativeBbox
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity

object BboxClusteringHelper {
    /**
     * Greedy clustering. For each class, sort detections by confidence DESC and grow clusters
     * by adding detections whose IoU with the cluster's running union is above [innerIou].
     * Each cluster yields one [RepresentativeBbox] computed as a confidence-weighted average.
     */
    fun cluster(
        detections: List<DetectionEntity>,
        innerIou: Float,
        confidenceFloor: Float = 0.3f,
    ): List<RepresentativeBbox> {
        if (detections.isEmpty()) return emptyList()

        return detections
            .filter { it.confidence >= confidenceFloor }
            .groupBy { it.className }
            .flatMap { (className, group) ->
                clusterOneClass(className, group.sortedByDescending { it.confidence }, innerIou)
            }
    }

    private data class WorkingCluster(
        var unionX1: Float, var unionY1: Float, var unionX2: Float, var unionY2: Float,
        var weightedX1: Float, var weightedY1: Float, var weightedX2: Float, var weightedY2: Float,
        var weightSum: Float,
    )

    private fun clusterOneClass(
        className: String,
        sortedByConfidenceDesc: List<DetectionEntity>,
        innerIou: Float,
    ): List<RepresentativeBbox> {
        val clusters = mutableListOf<WorkingCluster>()
        for (det in sortedByConfidenceDesc) {
            val matched = clusters.firstOrNull { c ->
                IouHelper.iou(c.unionX1, c.unionY1, c.unionX2, c.unionY2, det.x1, det.y1, det.x2, det.y2) > innerIou
            }
            if (matched != null) {
                addToCluster(matched, det)
            } else {
                clusters += newCluster(det)
            }
        }
        return clusters.map { c ->
            val w = c.weightSum
            if (w <= 0f) {
                // Guard against NaN from all-zero-confidence detections.
                RepresentativeBbox(
                    className = className,
                    x1 = c.unionX1, y1 = c.unionY1, x2 = c.unionX2, y2 = c.unionY2,
                )
            } else {
                RepresentativeBbox(
                    className = className,
                    x1 = c.weightedX1 / w,
                    y1 = c.weightedY1 / w,
                    x2 = c.weightedX2 / w,
                    y2 = c.weightedY2 / w,
                )
            }
        }
    }

    private fun newCluster(det: DetectionEntity): WorkingCluster {
        val w = det.confidence
        return WorkingCluster(
            unionX1 = det.x1, unionY1 = det.y1, unionX2 = det.x2, unionY2 = det.y2,
            weightedX1 = det.x1 * w, weightedY1 = det.y1 * w,
            weightedX2 = det.x2 * w, weightedY2 = det.y2 * w,
            weightSum = w,
        )
    }

    private fun addToCluster(c: WorkingCluster, det: DetectionEntity) {
        c.unionX1 = minOf(c.unionX1, det.x1)
        c.unionY1 = minOf(c.unionY1, det.y1)
        c.unionX2 = maxOf(c.unionX2, det.x2)
        c.unionY2 = maxOf(c.unionY2, det.y2)
        val w = det.confidence
        c.weightedX1 += det.x1 * w
        c.weightedY1 += det.y1 * w
        c.weightedX2 += det.x2 * w
        c.weightedY2 += det.y2 * w
        c.weightSum += w
    }
}
