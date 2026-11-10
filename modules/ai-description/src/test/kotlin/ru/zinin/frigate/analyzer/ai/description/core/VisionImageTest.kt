package ru.zinin.frigate.analyzer.ai.description.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VisionImageTest {
    @Test
    fun `an image is an ordinary frame unless the task marks it otherwise`() {
        assertEquals(VisionImage.Role.FRAME, VisionImage(byteArrayOf(1, 2, 3), "Frame 0").role)
    }

    @Test
    fun `images with the same bytes, caption and role are equal and hash alike`() {
        val first = VisionImage(byteArrayOf(1, 2, 3), "Event grid", VisionImage.Role.STORYBOARD)
        val second = VisionImage(byteArrayOf(1, 2, 3), "Event grid", VisionImage.Role.STORYBOARD)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    /** Иначе executor не отличил бы сетку от кадра с теми же байтами и выбрал бы ей чужой потолок. */
    @Test
    fun `the same bytes and caption in another role are a different image`() {
        val frame = VisionImage(byteArrayOf(1, 2, 3), "Event grid", VisionImage.Role.FRAME)
        val storyboard = VisionImage(byteArrayOf(1, 2, 3), "Event grid", VisionImage.Role.STORYBOARD)

        assertNotEquals(frame, storyboard)
        assertEquals(2, setOf(frame, storyboard).size)
    }

    /** Строка идёт в DEBUG-логи: роль и размер в ней нужны, содержимое картинки — нет. */
    @Test
    fun `toString names the role and the size but does not dump the bytes`() {
        val text = VisionImage(byteArrayOf(10, 20, 30), "Event grid", VisionImage.Role.STORYBOARD).toString()

        assertTrue("STORYBOARD" in text, text)
        assertTrue("3B" in text, text)
        assertFalse("10, 20, 30" in text, text)
        assertFalse("[B@" in text, text)
    }
}
