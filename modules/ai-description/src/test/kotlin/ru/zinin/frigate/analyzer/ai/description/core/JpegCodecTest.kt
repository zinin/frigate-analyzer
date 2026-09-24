package ru.zinin.frigate.analyzer.ai.description.core

import java.awt.image.BufferedImage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JpegCodecTest {
    // Шум, а не заливка: сплошной кадр сжимается почти в ноль при любом качестве.
    private fun noise(
        width: Int,
        height: Int,
    ): BufferedImage {
        val random = Random(42)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) {
            for (y in 0 until height) {
                image.setRGB(x, y, random.nextInt(0x1000000))
            }
        }
        return image
    }

    @Test
    fun `a round trip keeps the size`() {
        val image = JpegCodec.decode(JpegCodec.encode(noise(64, 48), 0.85f))

        assertEquals(64 to 48, image?.let { it.width to it.height })
    }

    @Test
    fun `bytes that are not an image decode to null`() {
        assertNull(JpegCodec.decode(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(JpegCodec.decode(ByteArray(0)))
    }

    @Test
    fun `a lower quality gives fewer bytes`() {
        val image = noise(64, 48)

        assertTrue(JpegCodec.encode(image, 0.3f).size < JpegCodec.encode(image, 0.9f).size)
    }
}
