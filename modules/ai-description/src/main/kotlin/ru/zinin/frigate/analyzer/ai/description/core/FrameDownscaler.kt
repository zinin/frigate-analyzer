package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

/**
 * Уменьшает кадр до заданной длинной стороны перед отправкой модели. Кадры приходят в разрешении
 * камеры: vision-эндпоинты считают токены по площади, а некоторые шлюзы молча выбрасывают картинку
 * больше своего предела (LiteLLM перед DKS-Vision — больше 1568 px по длинной стороне), и модель
 * отвечает «кадр недоступен», не сообщая причины.
 *
 * Всё, что не удалось прочитать или закодировать, возвращается как было: провайдер сам решит, что
 * делать с исходными байтами, а описание не должно падать из-за одного странного кадра. Поэтому
 * ловится не только [IOException]: плагины ImageIO бросают на битых данных и unchecked-исключения,
 * а `BufferedImage` — [IllegalArgumentException] на вырожденных размерах.
 */
object FrameDownscaler {
    /**
     * Качество JPEG на выходе: 0.85 держит текст оверлея камеры читаемым. Байт при этом может
     * получиться и больше, если исходный кадр был сжат сильнее, — это нормально: и лимиты
     * шлюзов, и счёт токенов идут по пикселям, а не по длине файла.
     */
    private const val JPEG_QUALITY = 0.85f

    fun downscale(
        bytes: ByteArray,
        maxSide: Int,
    ): ByteArray {
        if (maxSide <= 0 || bytes.isEmpty()) return bytes
        val source =
            tryOrWarn("Frame is not a readable image (${bytes.size} bytes); sending it unchanged") {
                ImageIO.read(ByteArrayInputStream(bytes))
            } ?: return bytes

        val longest = max(source.width, source.height)
        if (longest <= maxSide) return bytes

        val scale = maxSide.toDouble() / longest
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        return tryOrWarn("Cannot re-encode a ${source.width}x${source.height} frame; sending it unchanged") {
            encodeJpeg(resize(source, width, height))
        } ?: bytes
    }

    /** `null` и WARN вместо исключения: кадр уйдёт как есть, но описание будет получено. */
    private inline fun <T : Any> tryOrWarn(
        message: String,
        block: () -> T?,
    ): T? =
        try {
            block()
        } catch (e: IOException) {
            logger.warn(e) { message }
            null
        } catch (e: RuntimeException) {
            logger.warn(e) { message }
            null
        }

    private fun resize(
        source: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        // TYPE_INT_RGB, а не тип источника: кадры камеры это JPEG без альфы, а серые и палитровые
        // изображения иначе пришлось бы конвертировать отдельно перед JPEG-кодированием.
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer =
            ImageIO.getImageWritersByFormatName("jpeg").takeIf { it.hasNext() }?.next()
                ?: throw IOException("No JPEG writer available")
        val output = ByteArrayOutputStream()
        try {
            // Явно memory-cache: ImageIO.createImageOutputStream по умолчанию (ImageIO.getUseCache)
            // заводит временный файл на каждый кадр, а на read-only или переполненном java.io.tmpdir
            // ещё и бросает — кадр ушёл бы в полном разрешении, ровно то, что тут и предотвращается.
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
}
