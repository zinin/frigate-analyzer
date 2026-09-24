package ru.zinin.frigate.analyzer.ai.description.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * Чтение и запись JPEG в памяти. Потоки ImageIO по умолчанию (`ImageIO.getUseCache`) кэшируются во
 * временном файле в `java.io.tmpdir` — по файлу на каждое чтение и каждую запись, а на отсутствующем,
 * read-only или переполненном каталоге ImageIO бросает. Картинки здесь — сотни килобайт, и буфер в памяти
 * почти ничего не стоит. Общий для [FrameDownscaler] и сборщика раскадровки в `core`; качество каждый
 * передаёт своё.
 */
object JpegCodec {
    /**
     * Картинка из [bytes] (любой формат, который знает ImageIO) или `null`, если ни один reader её не
     * узнал. Если reader нашёлся, а данные битые, — [IOException].
     */
    fun decode(bytes: ByteArray): BufferedImage? {
        val stream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        // Не use: прочитав картинку, ImageIO.read закрывает поток сам, и повторный close бросает. Открытым
        // поток остаётся, только если reader не нашёлся и вернулся null.
        return ImageIO.read(stream) ?: run {
            stream.close()
            null
        }
    }

    /** JPEG с качеством [quality] от 0 до 1. */
    fun encode(
        image: BufferedImage,
        quality: Float,
    ): ByteArray {
        val writer =
            ImageIO.getImageWritersByFormatName("jpeg").takeIf { it.hasNext() }?.next()
                ?: throw IOException("No JPEG writer available")
        val output = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                val params =
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality
                    }
                writer.write(null, IIOImage(image, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }
}
