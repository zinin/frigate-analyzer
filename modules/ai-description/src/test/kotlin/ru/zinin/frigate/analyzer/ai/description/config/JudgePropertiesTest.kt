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
    fun `a max-snooze shorter than a minute is rejected`() {
        // Потолок выражен в целых минутах. PT30S проходил валидацию как «положительная длительность»,
        // а `maxSnoozeMinutes` округлял его ВВЕРХ до единицы — потолок оказывался вдвое выше
        // заданного, и модель могла усыпить камеру на минуту там, где владелец разрешил тридцать
        // секунд.
        val e =
            assertFailsWith<IllegalArgumentException> {
                JudgeProperties(maxSnooze = Duration.ofSeconds(30))
            }
        assertTrue(e.message!!.contains("max-snooze"), e.message)
    }

    @Test
    fun `staticIou above 1 is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                JudgeProperties(staticIou = 1.5)
            }
        assertTrue(e.message!!.contains("static-iou") || e.message!!.contains("staticIou"), e.message)
    }

    @Test
    fun `storyboard defaults match the documented judge settings`() {
        val storyboard = JudgeProperties().storyboard
        assertEquals(true, storyboard.enabled)
        assertEquals(2560, storyboard.maxImageSide)
        assertEquals(Duration.ofSeconds(10), storyboard.timeout)
    }

    @Test
    fun `storyboard max-image-side accepts 0 and the bounds of 256 to 8192`() {
        listOf(0, 256, 8192).forEach { cap ->
            val props = JudgeProperties(storyboard = JudgeProperties.StoryboardSettings(maxImageSide = cap))
            assertEquals(cap, props.storyboard.maxImageSide)
        }
    }

    @Test
    fun `storyboard max-image-side outside 0 and 256 to 8192 is rejected`() {
        listOf(-1, 255, 8193).forEach { cap ->
            val e =
                assertFailsWith<IllegalArgumentException>("max-image-side=$cap") {
                    JudgeProperties(storyboard = JudgeProperties.StoryboardSettings(maxImageSide = cap))
                }
            assertTrue(e.message!!.contains("storyboard.max-image-side"), e.message)
        }
    }

    /**
     * Бюджет считается в целых миллисекундах, как и остальные таймауты судьи: положительное значение
     * меньше миллисекунды стало бы нулём и обрывало бы каждую сборку, поэтому отклоняется вместе с
     * нулём и отрицательным.
     */
    @Test
    fun `a storyboard timeout that is not a positive number of milliseconds is rejected`() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(999_999)).forEach { timeout ->
            val e =
                assertFailsWith<IllegalArgumentException>("timeout=$timeout") {
                    JudgeProperties(storyboard = JudgeProperties.StoryboardSettings(timeout = timeout))
                }
            assertTrue(e.message!!.contains("storyboard.timeout"), e.message)
        }
        val oneMillisecond = JudgeProperties.StoryboardSettings(timeout = Duration.ofMillis(1))
        assertEquals(Duration.ofMillis(1), JudgeProperties(storyboard = oneMillisecond).storyboard.timeout)
    }
}
