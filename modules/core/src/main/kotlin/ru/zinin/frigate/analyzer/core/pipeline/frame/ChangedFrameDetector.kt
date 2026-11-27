package ru.zinin.frigate.analyzer.core.pipeline.frame

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.core.JpegCodec
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.model.dto.FrameData
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

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

        val failed = mutableListOf<Int>()
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
            logger.warn {
                "Changed-frame check: could not read frames $failed of recording ${frames.first().recordId}; " +
                    "analyzing them as usual"
            }
        }
        return result
    }

    /** `null` — кадр не прочитан или не сравнён: он обрабатывается как раньше, а запись не страдает. */
    private fun <T : Any> attempt(
        frame: FrameData,
        failed: MutableList<Int>,
        block: (FrameData) -> T?,
    ): T? =
        try {
            block(frame)
        } catch (e: Exception) {
            logger.debug(e) { "Changed-frame check: frame ${frame.frameIndex} of recording ${frame.recordId}" }
            null
        }.also { if (it == null) failed += frame.frameIndex }

    private fun read(frame: FrameData): WorkingFrame? = JpegCodec.decode(frame.frameBytes)?.let(::workingFrame)

    /** Уменьшенная копия кадра: яркость с размытием 3×3 и средняя цветность. */
    private class WorkingFrame(
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

        val data = (source.raster.dataBuffer as DataBufferByte).data
        val blue = IntArray(width * height)
        val green = IntArray(width * height)
        val red = IntArray(width * height)
        for (y in 0 until height * factor) {
            var offset = y * source.width * BGR_BYTES
            val row = (y / factor) * width
            for (x in 0 until width * factor) {
                val i = row + x / factor
                blue[i] += data[offset].toInt() and BYTE_MASK
                green[i] += data[offset + 1].toInt() and BYTE_MASK
                red[i] += data[offset + 2].toInt() and BYTE_MASK
                offset += BGR_BYTES
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
        return WorkingFrame(width, height, blur(luma, width, height), spread.toDouble() / counted)
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
        if (reference.width != current.width || reference.height != current.height) return false
        val width = current.width
        val height = current.height
        // Общее изменение яркости (облако, экспозиция) — не изменение сцены.
        val shift = median(current.luma) - median(reference.luma)
        val mask = BooleanArray(width * height)
        // Верхняя полоса — часы камеры, они меняются в каждом кадре.
        for (i in (height * TOP_STRIP).toInt() * width until mask.size) {
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

    private fun blur(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): IntArray =
        IntArray(pixels.size) { i ->
            val x = i % width
            val y = i / width
            var sum = 0
            for (dy in -1..1) {
                val row = (y + dy).coerceIn(0, height - 1) * width
                for (dx in -1..1) {
                    sum += pixels[row + (x + dx).coerceIn(0, width - 1)]
                }
            }
            (sum + 4) / 9
        }

    private fun median(values: IntArray): Int {
        val histogram = IntArray(BYTE_MASK + 1)
        values.forEach { histogram[it]++ }
        var seen = 0
        for (value in histogram.indices) {
            seen += histogram[value]
            if (seen * 2 >= values.size) return value
        }
        return BYTE_MASK
    }

    private fun erode(
        mask: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
    ) = morph(mask, width, height, radius, keep = true)

    private fun dilate(
        mask: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
    ) = morph(mask, width, height, radius, keep = false)

    /** Прямоугольная морфология в два прохода: `keep = true` — эрозия, `false` — расширение. */
    private fun morph(
        mask: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
        keep: Boolean,
    ): BooleanArray {
        val horizontal =
            BooleanArray(mask.size) { i ->
                val x = i % width
                val row = i - x
                val hit = (-radius..radius).any { mask[row + (x + it).coerceIn(0, width - 1)] != keep }
                if (hit) !keep else keep
            }
        return BooleanArray(mask.size) { i ->
            val x = i % width
            val y = i / width
            val hit = (-radius..radius).any { horizontal[(y + it).coerceIn(0, height - 1) * width + x] != keep }
            if (hit) !keep else keep
        }
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
