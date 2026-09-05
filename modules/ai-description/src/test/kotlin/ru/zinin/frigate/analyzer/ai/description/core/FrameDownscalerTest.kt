package ru.zinin.frigate.analyzer.ai.description.core

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FrameDownscalerTest {
    private fun jpeg(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        // Не заливка одним цветом: сплошной кадр сжимается до пары сотен байт, и проверка
        // «файл стал меньше» перестала бы что-либо значить.
        repeat(40) { i ->
            graphics.color = Color(i * 6 % 256, (i * 13) % 256, (i * 29) % 256)
            graphics.fillRect(i * width / 40, 0, width / 40 + 1, height)
        }
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", out)
        return out.toByteArray()
    }

    private fun sizeOf(bytes: ByteArray): Pair<Int, Int> = ImageIO.read(ByteArrayInputStream(bytes)).let { it.width to it.height }

    @Test
    fun `a frame larger than the limit is scaled to it and keeps the aspect ratio`() {
        val source = jpeg(1920, 1080)

        val result = FrameDownscaler.downscale(source, 1568)

        assertEquals(1568 to 882, sizeOf(result))
        // Проверяется геометрия, а не длина массива: перекодирование в q0.85 может дать файл
        // тяжелее исходного, если тот был сжат сильнее. Токены и лимиты шлюзов считаются по
        // пикселям, поэтому уменьшение стороны это и есть результат.
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `a portrait frame is limited by its longest side too`() {
        assertEquals(720 to 1280, sizeOf(FrameDownscaler.downscale(jpeg(1080, 1920), 1280)))
    }

    @Test
    fun `a frame within the limit is returned untouched`() {
        val source = jpeg(800, 600)

        assertSame(source, FrameDownscaler.downscale(source, 1568))
    }

    @Test
    fun `zero and negative limits disable the resize`() {
        val source = jpeg(1920, 1080)

        assertSame(source, FrameDownscaler.downscale(source, 0))
        assertSame(source, FrameDownscaler.downscale(source, -1))
    }

    @Test
    fun `bytes that are not an image are passed through instead of failing the description`() {
        val garbage = byteArrayOf(1, 2, 3, 4, 5)
        val empty = ByteArray(0)

        assertSame(garbage, FrameDownscaler.downscale(garbage, 1568))
        assertSame(empty, FrameDownscaler.downscale(empty, 1568))
    }
}
