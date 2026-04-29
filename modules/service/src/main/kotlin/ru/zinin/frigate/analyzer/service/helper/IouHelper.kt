package ru.zinin.frigate.analyzer.service.helper

import ru.zinin.frigate.analyzer.model.dto.RepresentativeBbox

object IouHelper {
    /**
     * Standard Intersection-over-Union for axis-aligned bboxes.
     *
     * Returns `0f` when the union area is non-positive (zero-area bboxes — even at identical
     * coordinates — and disjoint bboxes both yield 0). YOLO does not emit zero-area boxes in
     * practice, so this convention has no impact on production data.
     */
    @Suppress("LongParameterList")
    fun iou(
        ax1: Float,
        ay1: Float,
        ax2: Float,
        ay2: Float,
        bx1: Float,
        by1: Float,
        bx2: Float,
        by2: Float,
    ): Float {
        val ix1 = maxOf(ax1, bx1)
        val iy1 = maxOf(ay1, by1)
        val ix2 = minOf(ax2, bx2)
        val iy2 = minOf(ay2, by2)
        val iw = maxOf(0f, ix2 - ix1)
        val ih = maxOf(0f, iy2 - iy1)
        val intersection = iw * ih

        // Clamp area inputs to non-negative — defensive against malformed (x1 > x2 or y1 > y2)
        // bboxes upstream that would otherwise produce a negative union and a wrong IoU sign.
        val areaA = maxOf(0f, ax2 - ax1) * maxOf(0f, ay2 - ay1)
        val areaB = maxOf(0f, bx2 - bx1) * maxOf(0f, by2 - by1)
        val union = areaA + areaB - intersection

        return if (union > 0f) intersection / union else 0f
    }

    fun iou(
        a: RepresentativeBbox,
        b: RepresentativeBbox,
    ): Float = iou(a.x1, a.y1, a.x2, a.y2, b.x1, b.y1, b.x2, b.y2)
}
