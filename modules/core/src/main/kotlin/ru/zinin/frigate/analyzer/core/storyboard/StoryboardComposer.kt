package ru.zinin.frigate.analyzer.core.storyboard

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max

/**
 * Собирает клетки раскадровки в одну сетку: слева направо и сверху вниз, подпись на тёмной подложке
 * в левом верхнем углу каждой клетки. Размер клетки — размер первого кадра; пустые ячейки последнего
 * ряда остаются чёрными. Кадры читаются и JPEG пишется в памяти, без временных файлов.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class StoryboardComposer {
    /** Клетка: JPEG кадра, подпись и выделять ли её как ближайшую к детекции. */
    class Tile(
        val image: ByteArray,
        val label: String,
        val marked: Boolean,
    )

    fun compose(
        tiles: List<Tile>,
        layout: GridLayout,
    ): ByteArray {
        require(tiles.isNotEmpty()) { "tiles must not be empty" }
        require(tiles.size <= layout.columns * layout.rows) {
            "${tiles.size} tiles do not fit a ${layout.columns}x${layout.rows} grid"
        }
        val images = tiles.map { decode(it.image) }
        val width = images.first().width
        val height = images.first().height
        val canvas = BufferedImage(width * layout.columns, height * layout.rows, BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.font = labelFont(graphics, tiles.map { it.label }, width, height)
            images.forEachIndexed { index, image ->
                val x = (index % layout.columns) * width
                val y = (index / layout.columns) * height
                graphics.drawImage(image, x, y, width, height, null)
                drawLabel(graphics, tiles[index], x, y)
            }
        } finally {
            graphics.dispose()
        }
        return encodeJpeg(canvas)
    }

    /**
     * Кадр читается из памяти: `ImageIO.read(InputStream)` по умолчанию кэширует поток во временном файле —
     * по файлу на клетку, а без доступного `java.io.tmpdir` бросает.
     */
    private fun decode(bytes: ByteArray): BufferedImage {
        val stream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        // Не use: прочитав кадр, ImageIO.read закрывает поток сам, и повторный close бросает. Открытым поток
        // остаётся, только если reader не нашёлся и вернулся null.
        return ImageIO.read(stream) ?: run {
            stream.close()
            throw IllegalArgumentException("A storyboard tile is not a readable image")
        }
    }

    /**
     * Шрифт подписей, один на всю сетку: высотой около 1/10 клетки, но не меньше [MIN_FONT_SIZE]. У вертикальной
     * камеры клетка узкая и высокая, и `12.3s • detection` в 1/10 её высоты шире самой клетки: конец подписи
     * затёрла бы следующая клетка. Поэтому берётся самый крупный размер не больше этого, при котором каждая
     * подпись вместе с подложкой помещается в ширину клетки; у альбомной камеры это те же 1/10 высоты. Если не
     * помещается и [MIN_FONT_SIZE] — клетка уже ~110 px, а в проде она не уже 512 px, — остаётся он.
     */
    private fun labelFont(
        graphics: Graphics2D,
        labels: List<String>,
        width: Int,
        height: Int,
    ): Font {
        val size =
            (max(MIN_FONT_SIZE, height / FONT_DIVISOR) downTo MIN_FONT_SIZE).firstOrNull { size ->
                val metrics = graphics.getFontMetrics(Font(Font.SANS_SERIF, Font.BOLD, size))
                labels.all { plateWidth(metrics, it) <= width }
            } ?: MIN_FONT_SIZE
        return Font(Font.SANS_SERIF, Font.BOLD, size)
    }

    private fun drawLabel(
        graphics: Graphics2D,
        tile: Tile,
        x: Int,
        y: Int,
    ) {
        val metrics = graphics.fontMetrics
        val padding = padding(metrics)
        graphics.color = LABEL_BACKGROUND
        graphics.fillRect(x, y, plateWidth(metrics, tile.label), metrics.height + padding * 2)
        graphics.color = if (tile.marked) MARKED_TEXT else Color.WHITE
        graphics.drawString(tile.label, x + padding, y + padding + metrics.ascent)
    }

    /** Поле подложки вокруг текста подписи. */
    private fun padding(metrics: FontMetrics): Int = max(2, metrics.height / 4)

    /** Ширина подложки: текст подписи и поля с обеих сторон. */
    private fun plateWidth(
        metrics: FontMetrics,
        label: String,
    ): Int = metrics.stringWidth(label) + padding(metrics) * 2

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer =
            ImageIO.getImageWritersByFormatName("jpeg").takeIf { it.hasNext() }?.next()
                ?: throw IOException("No JPEG writer available")
        val output = ByteArrayOutputStream()
        try {
            // Memory-cache, а не ImageIO.createImageOutputStream: тот по умолчанию заводит временный файл.
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                val params =
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = JPEG_QUALITY
                    }
                writer.write(null, IIOImage(image, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    private companion object {
        const val MIN_FONT_SIZE = 12
        const val FONT_DIVISOR = 10
        const val JPEG_QUALITY = 0.85f
        val LABEL_BACKGROUND = Color(0, 0, 0, 153)
        val MARKED_TEXT = Color(255, 214, 0)
    }
}
