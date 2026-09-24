package ru.zinin.frigate.analyzer.core.storyboard

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryboardComposerTest {
    private val composer = StoryboardComposer()

    private fun solid(
        color: Color,
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun tile(
        color: Color,
        label: String = "0.0s",
        marked: Boolean = false,
        width: Int = 200,
        height: Int = 120,
    ) = StoryboardComposer.Tile(solid(color, width, height), label, marked)

    private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    private fun BufferedImage.colorAt(
        x: Int,
        y: Int,
    ) = Color(getRGB(x, y))

    private fun assertAbout(
        expected: Color,
        actual: Color,
    ) {
        val distance = abs(expected.red - actual.red) + abs(expected.green - actual.green) + abs(expected.blue - actual.blue)
        assertTrue(distance < 60, "expected about $expected, got $actual")
    }

    @Test
    fun `the canvas is columns by rows of tiles`() {
        val image = decode(composer.compose(List(16) { tile(Color.GRAY) }, GridLayout(4, 4)))

        assertEquals(800, image.width)
        assertEquals(480, image.height)
    }

    @Test
    fun `tiles go left to right, top to bottom, and an unused cell stays black`() {
        val image = decode(composer.compose(listOf(tile(Color.RED), tile(Color.GREEN), tile(Color.BLUE)), GridLayout(2, 2)))

        assertAbout(Color.RED, image.colorAt(100, 60))
        assertAbout(Color.GREEN, image.colorAt(300, 60))
        assertAbout(Color.BLUE, image.colorAt(100, 180))
        assertAbout(Color.BLACK, image.colorAt(300, 180))
    }

    @Test
    fun `a portrait camera keeps its proportions`() {
        val image = decode(composer.compose(List(4) { tile(Color.GRAY, width = 90, height = 160) }, GridLayout(2, 2)))

        assertEquals(180, image.width)
        assertEquals(320, image.height)
    }

    @Test
    fun `every tile carries its label on a dark plate`() {
        val image = decode(composer.compose(listOf(tile(Color.GREEN, label = "5.0s")), GridLayout(1, 1)))

        val plate = image.colorAt(1, 1)
        assertTrue(plate.green < 150, "the label plate must darken the corner, got $plate")
        assertAbout(Color.GREEN, image.colorAt(150, 100))
    }

    @Test
    fun `a marked tile is drawn differently from an unmarked one`() {
        val plain = composer.compose(listOf(tile(Color.GRAY, label = "5.0s", marked = false)), GridLayout(1, 1))
        val marked = composer.compose(listOf(tile(Color.GRAY, label = "5.0s", marked = true)), GridLayout(1, 1))

        assertFalse(plain.contentEquals(marked))
    }

    @Test
    fun `more tiles than cells is a programming error`() {
        assertFailsWith<IllegalArgumentException> { composer.compose(List(5) { tile(Color.GRAY) }, GridLayout(2, 2)) }
    }

    @Test
    fun `a tile that is not an image is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            composer.compose(listOf(StoryboardComposer.Tile(byteArrayOf(1, 2, 3), "0.0s", false)), GridLayout(1, 1))
        }
    }
}
