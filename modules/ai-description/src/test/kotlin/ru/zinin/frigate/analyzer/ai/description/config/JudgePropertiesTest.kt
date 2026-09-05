package ru.zinin.frigate.analyzer.ai.description.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JudgePropertiesTest {
    @Test
    fun `defaults match the documented judge settings`() {
        val props = JudgeProperties()
        assertEquals(false, props.enabled)
        assertEquals("", props.defaultPreset)
        assertEquals(4, props.maxFrames)
        assertEquals(1280, props.maxImageSide)
        assertEquals(200, props.rateLimit.maxRequests)
        assertEquals(Duration.ofMinutes(30), props.maxSnooze)
        assertEquals(Duration.ofDays(7), props.staticWindow)
        assertEquals(0.4, props.staticIou)
        assertEquals(Duration.ofHours(6), props.historyWindow)
        assertEquals(10, props.historyLimit)
        assertEquals("", props.zone)
        assertTrue(props.cameras.isEmpty())
        assertEquals(30, props.maxSnoozeMinutes)
    }

    @Test
    fun `a negative timeout is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                JudgeProperties(timeout = Duration.ofSeconds(-1))
            }
        assertTrue(e.message!!.contains("timeout"), e.message)
    }

    @Test
    fun `staticIou above 1 is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                JudgeProperties(staticIou = 1.5)
            }
        assertTrue(e.message!!.contains("static-iou") || e.message!!.contains("staticIou"), e.message)
    }
}
