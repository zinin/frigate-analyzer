package ru.zinin.frigate.analyzer.core.pipeline.frame

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.core.JpegCodec
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.model.dto.FrameData
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

/** Кадр, который не удалось прочитать или сравнить, и почему. */
internal data class FrameFailure(
    val frameIndex: Int,
    val cause: String,
)

/** Один WARN на запись с первой причиной: без неё режим мог бы молча перестать работать. */
internal fun unreadableFramesWarning(
    recordId: UUID,
    failures: List<FrameFailure>,
): String =
    "Changed-frame check: could not read or compare frames ${failures.map { it.frameIndex }} of recording $recordId " +
        "(first cause: ${failures.first().cause}); analyzing them as usual"

/** Какие кадры записи отличаются от первого и почему сравнение не делалось, если не делалось. */
data class ChangedFrames(
    val indexes: Set<Int> = emptySet(),
    val skip: Skip? = null,
) {
    enum class Skip { DISABLED, NOTHING_TO_COMPARE, MONOCHROME, FAILED }
}

/**
 * Находит кадры, в которых что-то изменилось по сравнению с первым кадром записи. Такой кадр
 * анализируется сразу good-model: малая модель не видит мелкие далёкие объекты, а гонять большую на
 * всех кадрах слишком дорого.
 *
 * Только для дневных записей: ночной ИК-режим камеры даёт строго серую картинку, а падающий снег в нём
 * «меняет» большую часть кадров. Пороги взяты из прототипа, на котором мерили эффект (920 записей:
 * +12 записей с объектами, 0 ложных на 600 пустых) — менять их без повторного замера не стоит.
 *
 * Не бросает: любой сбой даёт прежнее поведение — кадр не помечается.
 */
@Component
class ChangedFrameDetector(
    private val detectProperties: DetectProperties,
) {
    fun detect(frames: List<FrameData>): ChangedFrames {
        if (!detectProperties.changedFrames.enabled) return ChangedFrames(skip = ChangedFrames.Skip.DISABLED)
        if (frames.size < 2) return ChangedFrames(skip = ChangedFrames.Skip.NOTHING_TO_COMPARE)

        val failed = mutableListOf<FrameFailure>()
        val reference = attempt(frames.first(), failed) { read(it) }
        val result =
            when {
                reference == null -> {
                    ChangedFrames(skip = ChangedFrames.Skip.FAILED)
                }

                reference.colorSpread <= MONOCHROME_SPREAD -> {
                    ChangedFrames(skip = ChangedFrames.Skip.MONOCHROME)
                }

                else -> {
                    ChangedFrames(
                        indexes =
                            frames
                                .drop(1)
                                .filter { frame ->
                                    attempt(frame, failed) { read(it)?.let { current -> differs(reference, current) } } == true
                                }.map { it.frameIndex }
                                .toSet(),
                    )
                }
            }

        if (failed.isNotEmpty()) {
            logger.warn { unreadableFramesWarning(frames.first().recordId, failed) }
        }
        return result
    }

    /** `null` — кадр не прочитан или не сравнён: он обрабатывается как раньше, а запись не страдает. */
    private fun <T : Any> attempt(
        frame: FrameData,
        failed: MutableList<FrameFailure>,
        block: (FrameData) -> T?,
    ): T? {
        val result =
            try {
                block(frame)
            } catch (e: Throwable) {
                // Throwable, а не Exception: Error — OOM на большом кадре, LinkageError в Java2D — вышел бы из
                // производителя кадров и остановил бы его, а не пропустил бы проверку одного кадра. detect не
                // suspend, так что отмены здесь не бывает.
                logger.debug(e) { "Changed-frame check: frame ${frame.frameIndex} of recording ${frame.recordId}" }
                failed += FrameFailure(frame.frameIndex, "${e.javaClass.simpleName}: ${e.message}")
                return null
            }
        if (result == null) failed += FrameFailure(frame.frameIndex, "not a recognised image")
        return result
    }

    private fun read(frame: FrameData): WorkingFrame? = JpegCodec.decode(frame.frameBytes)?.let(::workingFrame)

    /** Уменьшенная копия кадра: яркость с размытием 3×3 и средняя цветность, плюс размер исходного кадра. */
    private class WorkingFrame(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val width: Int,
        val height: Int,
        val luma: IntArray,
        val colorSpread: Double,
    )

    /**
     * Уменьшает кадр до ширины около [WORK_WIDTH], усредняя блоки f×f: прореживание пикселей
     * сделало бы ветки и снег источником ложных изменений.
     */
    private fun workingFrame(image: BufferedImage): WorkingFrame? {
        val source = asBgr(image)
        val factor = max(1, source.width / WORK_WIDTH)
        val width = source.width / factor
        val height = source.height / factor
        if (width == 0 || height == 0) return null

        // Быстрый путь рассчитан на компактный растр, который выделяют ImageIO.read и asBgr (смещение 0, строка —
        // width × 3 байт), поэтому картинку из другого источника (например, подкартинку) сначала нужно скопировать:
        // asBgr копирует только картинку другого типа.
        val data = (source.raster.dataBuffer as DataBufferByte).data
        val blue = IntArray(width * height)
        val green = IntArray(width * height)
        val red = IntArray(width * height)
        // Это самый горячий цикл: без деления на каждый пиксель и с суммой по строке блока.
        for (y in 0 until height * factor) {
            var offset = y * source.width * BGR_BYTES
            val row = (y / factor) * width
            for (column in 0 until width) {
                var b = 0
                var g = 0
                var r = 0
                repeat(factor) {
                    b += data[offset].toInt() and BYTE_MASK
                    g += data[offset + 1].toInt() and BYTE_MASK
                    r += data[offset + 2].toInt() and BYTE_MASK
                    offset += BGR_BYTES
                }
                blue[row + column] += b
                green[row + column] += g
                red[row + column] += r
            }
        }

        val area = factor * factor
        val luma = IntArray(width * height)
        val top = (height * TOP_STRIP).toInt()
        var spread = 0L
        for (i in luma.indices) {
            val r = red[i] / area
            val g = green[i] / area
            val b = blue[i] / area
            luma[i] = (r * 299 + g * 587 + b * 114) / 1000
            if (i / width >= top) spread += maxOf(r, g, b) - minOf(r, g, b)
        }
        val counted = (height - top) * width
        return WorkingFrame(source.width, source.height, width, height, blur(luma, width, height), spread.toDouble() / counted)
    }

    private fun asBgr(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_3BYTE_BGR) return image
        val converted = BufferedImage(image.width, image.height, BufferedImage.TYPE_3BYTE_BGR)
        val graphics = converted.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return converted
    }

    private fun differs(
        reference: WorkingFrame,
        current: WorkingFrame,
    ): Boolean {
        // Кадр другого размера не сравнивается; при равных исходных размерах равны и рабочие.
        if (reference.sourceWidth != current.sourceWidth || reference.sourceHeight != current.sourceHeight) return false
        val width = current.width
        val height = current.height
        // Верхняя полоса — часы камеры, они меняются в каждом кадре: она не входит ни в общий сдвиг, ни в маску.
        val start = (height * TOP_STRIP).toInt() * width
        // Общее изменение яркости (облако, экспозиция) — не изменение сцены.
        val shift = median(current.luma, start) - median(reference.luma, start)
        val mask = BooleanArray(width * height)
        for (i in start until mask.size) {
            val expected = (reference.luma[i] + shift).coerceIn(0, BYTE_MASK)
            mask[i] = abs(current.luma[i] - expected) > PIXEL_THRESHOLD
        }
        // Открытие убирает одиночные пиксели и тонкие контуры, расширение склеивает части объекта.
        val opened = dilate(erode(mask, width, height, 1), width, height, 1)
        val cleaned = dilate(opened, width, height, 3)

        if (cleaned.count { it } > mask.size * STORM_SHARE) return true
        val minRegion = (MIN_REGION * (width.toDouble() / WORK_WIDTH).pow(2)).toInt()
        return hasRegion(cleaned, width, height, minRegion)
    }

    /** Размытие 3×3 с повтором краевых пикселей, в два прохода: сначала по строкам, потом по столбцам. */
    private fun blur(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): IntArray {
        val rows = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                rows[row + x] =
                    pixels[row + max(0, x - 1)] + pixels[row + x] + pixels[row + minOf(width - 1, x + 1)]
            }
        }
        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            val above = max(0, y - 1) * width
            val row = y * width
            val below = minOf(height - 1, y + 1) * width
            for (x in 0 until width) {
                result[row + x] = (rows[above + x] + rows[row + x] + rows[below + x] + 4) / 9
            }
        }
        return result
    }

    /** Медиана [values] с индекса [from] до конца. */
    private fun median(
        values: IntArray,
        from: Int,
    ): Int {
        val histogram = IntArray(BYTE_MASK + 1)
        for (i in from until values.size) histogram[values[i]]++
        val count = values.size - from
        var seen = 0
        for (value in histogram.indices) {
            seen += histogram[value]
            if (seen * 2 >= count) return value
        }
        return BYTE_MASK
    }

    /** Пиксель остаётся, только если всё окно `(2r+1)×(2r+1)` отмечено. */
    private fun erode(
        mask: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
    ) = window(window(mask, width, height, radius, spread = false, alongRows = true), width, height, radius, false, false)

    /** Пиксель отмечается, если в окне `(2r+1)×(2r+1)` есть отмеченный. */
    private fun dilate(
        mask: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
    ) = window(window(mask, width, height, radius, spread = true, alongRows = true), width, height, radius, true, false)

    /**
     * Один проход прямоугольной морфологии скользящим окном: пиксель получает значение [spread], если
     * оно есть хотя бы в одном пикселе окна вдоль строки (или столбца), иначе обратное. Край кадра не
     * дополняется — повтор краевого пикселя ничего не изменил бы.
     */
    private fun window(
        mask: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
        spread: Boolean,
        alongRows: Boolean,
    ): BooleanArray {
        val result = BooleanArray(mask.size)
        val lines = if (alongRows) height else width
        val length = if (alongRows) width else height
        val step = if (alongRows) 1 else width
        for (line in 0 until lines) {
            val start = if (alongRows) line * width else line
            var hits = 0
            for (k in 0..minOf(radius, length - 1)) if (mask[start + k * step] == spread) hits++
            for (k in 0 until length) {
                result[start + k * step] = if (hits > 0) spread else !spread
                val entering = k + radius + 1
                if (entering < length && mask[start + entering * step] == spread) hits++
                val leaving = k - radius
                if (leaving >= 0 && mask[start + leaving * step] == spread) hits--
            }
        }
        return result
    }

    /** Есть ли 8-связная область не меньше [minSize] пикселей. */
    private fun hasRegion(
        mask: BooleanArray,
        width: Int,
        height: Int,
        minSize: Int,
    ): Boolean {
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var top = 0
            var size = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val i = stack[--top]
                size++
                val x = i % width
                val y = i / width
                for (ny in max(0, y - 1)..minOf(height - 1, y + 1)) {
                    for (nx in max(0, x - 1)..minOf(width - 1, x + 1)) {
                        val j = ny * width + nx
                        if (mask[j] && !seen[j]) {
                            seen[j] = true
                            stack[top++] = j
                        }
                    }
                }
            }
            if (size >= minSize) return true
        }
        return false
    }

    private companion object {
        /** Рабочая ширина сравнения; [MIN_REGION] задан для неё и масштабируется квадратом ширины. */
        const val WORK_WIDTH = 640

        /** Средняя разность `max − min` каналов, не выше которой кадр считается ИК/серым. ИК — 0, день — от 4. */
        const val MONOCHROME_SPREAD = 2.0

        /** Разность яркости после компенсации общего сдвига, с которой пиксель считается изменившимся. */
        const val PIXEL_THRESHOLD = 22

        /** Доля кадра сверху, где камера рисует часы. */
        const val TOP_STRIP = 0.08

        /** Наименьшая площадь изменившейся области, пиксели при [WORK_WIDTH]. */
        const val MIN_REGION = 300

        /** Если изменилась такая доля кадра, кадр помечается без разбора областей (метель, свет). */
        const val STORM_SHARE = 0.08

        const val BGR_BYTES = 3
        const val BYTE_MASK = 0xFF
    }
}
