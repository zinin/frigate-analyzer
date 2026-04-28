package ru.zinin.frigate.analyzer.service.helper

import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.RepresentativeBbox
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IouHelperTest {
    private fun assertNear(
        expected: Float,
        actual: Float,
        eps: Float = 1e-5f,
    ) {
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")
    }

    @Test
    fun `identical bboxes return 1`() {
        val iou = IouHelper.iou(0.1f, 0.1f, 0.5f, 0.5f, 0.1f, 0.1f, 0.5f, 0.5f)
        assertNear(1.0f, iou)
    }

    @Test
    fun `disjoint bboxes return 0`() {
        val iou = IouHelper.iou(0f, 0f, 0.2f, 0.2f, 0.5f, 0.5f, 0.8f, 0.8f)
        assertEquals(0f, iou)
    }

    @Test
    fun `touching but not overlapping bboxes return 0`() {
        val iou = IouHelper.iou(0f, 0f, 0.5f, 0.5f, 0.5f, 0f, 1.0f, 0.5f)
        assertEquals(0f, iou)
    }

    @Test
    fun `half overlap returns one third`() {
        // A: [0,0]-[1,1] area=1; B: [0.5,0]-[1,1] area=0.5; intersection=0.5; union=1.0; iou=0.5
        val iou = IouHelper.iou(0f, 0f, 1f, 1f, 0.5f, 0f, 1f, 1f)
        assertNear(0.5f, iou)
    }

    @Test
    fun `fully contained returns ratio of areas`() {
        // A: [0,0]-[1,1] area=1; B: [0.25,0.25]-[0.75,0.75] area=0.25; iou = 0.25/1.0 = 0.25
        val iou = IouHelper.iou(0f, 0f, 1f, 1f, 0.25f, 0.25f, 0.75f, 0.75f)
        assertNear(0.25f, iou)
    }

    @Test
    fun `zero-area bbox returns 0`() {
        val iou = IouHelper.iou(0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f)
        assertEquals(0f, iou)
    }

    @Test
    fun `RepresentativeBbox overload delegates to raw overload`() {
        val a = RepresentativeBbox("car", 0f, 0f, 1f, 1f)
        val b = RepresentativeBbox("car", 0.5f, 0f, 1f, 1f)
        assertNear(0.5f, IouHelper.iou(a, b))
    }
}
