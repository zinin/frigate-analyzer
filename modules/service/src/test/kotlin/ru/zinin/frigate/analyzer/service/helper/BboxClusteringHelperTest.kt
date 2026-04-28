package ru.zinin.frigate.analyzer.service.helper

import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BboxClusteringHelperTest {
    private fun det(
        className: String,
        x1: Float, y1: Float, x2: Float, y2: Float,
        confidence: Float = 0.9f,
    ): DetectionEntity =
        DetectionEntity(
            id = UUID.randomUUID(),
            creationTimestamp = null,
            recordingId = null,
            detectionTimestamp = null,
            frameIndex = 0,
            model = "yolo",
            classId = 0,
            className = className,
            confidence = confidence,
            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
        )

    @Test
    fun `empty input yields empty output`() {
        val result = BboxClusteringHelper.cluster(emptyList(), innerIou = 0.5f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `one detection yields one bbox`() {
        val result = BboxClusteringHelper.cluster(listOf(det("car", 0f, 0f, 1f, 1f)), innerIou = 0.5f)
        assertEquals(1, result.size)
        assertEquals("car", result[0].className)
    }

    @Test
    fun `near-identical bboxes of same class merge into one cluster`() {
        val result = BboxClusteringHelper.cluster(
            listOf(
                det("car", 0.0f, 0.0f, 0.5f, 0.5f, confidence = 1.0f),
                det("car", 0.01f, 0.0f, 0.51f, 0.5f, confidence = 0.8f),
                det("car", -0.01f, 0.0f, 0.49f, 0.5f, confidence = 0.6f),
            ),
            innerIou = 0.5f,
        )
        assertEquals(1, result.size)
        // Confidence-weighted average should be close to the highest-confidence sample.
        val box = result[0]
        assertTrue(abs(box.x1 - 0.0f) < 0.01f, "x1=${box.x1}")
        assertTrue(abs(box.x2 - 0.5f) < 0.01f, "x2=${box.x2}")
    }

    @Test
    fun `same class but distant bboxes form two clusters`() {
        val result = BboxClusteringHelper.cluster(
            listOf(
                det("car", 0.0f, 0.0f, 0.2f, 0.2f),
                det("car", 0.7f, 0.7f, 0.9f, 0.9f),
            ),
            innerIou = 0.5f,
        )
        assertEquals(2, result.size)
    }

    @Test
    fun `different classes never merge`() {
        val result = BboxClusteringHelper.cluster(
            listOf(
                det("car", 0.0f, 0.0f, 0.5f, 0.5f),
                det("person", 0.0f, 0.0f, 0.5f, 0.5f),
            ),
            innerIou = 0.5f,
        )
        assertEquals(2, result.size)
        assertEquals(setOf("car", "person"), result.map { it.className }.toSet())
    }

    @Test
    fun `higher confidence detection seeds cluster center`() {
        val result = BboxClusteringHelper.cluster(
            listOf(
                det("car", 0.45f, 0.45f, 0.55f, 0.55f, confidence = 0.5f),
                det("car", 0.40f, 0.40f, 0.60f, 0.60f, confidence = 0.95f), // strongest, processed first
            ),
            innerIou = 0.2f,
        )
        assertEquals(1, result.size)
        // Bias toward the high-confidence one
        assertTrue(result[0].x1 < 0.43f, "x1=${result[0].x1}")
    }
}
