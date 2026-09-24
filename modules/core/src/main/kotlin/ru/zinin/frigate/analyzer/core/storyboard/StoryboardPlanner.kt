package ru.zinin.frigate.analyzer.core.storyboard

import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Отрезок на шкале текущей записи, в секундах от её начала; предыдущий сегмент — меньше нуля. */
data class TimeRange(
    val start: Double,
    val end: Double,
) {
    val length: Double get() = end - start
}

/** Запись, доступная раскадровке, и чего не хватило окну с каждой стороны. */
data class Footage(
    val range: TimeRange,
    val missingBefore: Boolean,
    val missingAfter: Boolean,
)

/** Моменты клеток на шкале записи и шаг между ними. */
data class TileGrid(
    val moments: List<Double>,
    val stepSeconds: Double,
)

enum class SegmentRole { PREVIOUS, CURRENT, NEXT }

/** Момент клетки внутри конкретного файла: какой сегмент и сколько секунд от его начала. */
data class SegmentMoment(
    val role: SegmentRole,
    val localSeconds: Double,
)

data class GridLayout(
    val columns: Int,
    val rows: Int,
)

/**
 * Арифметика раскадровки без ввода-вывода. Время — «время записи»: текущий сегмент занимает
 * `[0, D)`, предыдущий — `[-Dp, 0)`, следующий — `[D, D + Dn)`.
 */
object StoryboardPlanner {
    /** Клетки не чаще, чем раз в полсекунды: чаще соседние кадры почти не отличаются. */
    const val MIN_STEP_SECONDS = 0.5

    /** Меньше четырёх клеток — не раскадровка; описание идёт по одним кадрам. */
    const val MIN_TILES = 4

    /** Ширина сетки: Claude 4.7+ уменьшает картинку до 2576 px по длинной стороне. */
    const val GRID_WIDTH = 2560

    /** Последние 0.1 с отрезка не используются: кадр ровно на конце файла ffmpeg уже не отдаёт. */
    const val END_GUARD_SECONDS = 0.1

    /** Время начала в имени файла Frigate округлено до секунды; стык проверяется с этим допуском. */
    val CONTIGUITY_TOLERANCE: Duration = Duration.ofMillis(1500)

    /**
     * Окно вокруг детекций: от первой минус [before] до последней плюс [after]. Без времён детекций
     * окно — вся текущая запись, и соседи не нужны.
     */
    fun window(
        detectionSeconds: List<Double>,
        before: Double,
        after: Double,
        currentDuration: Double,
    ): TimeRange =
        if (detectionSeconds.isEmpty()) {
            TimeRange(0.0, currentDuration)
        } else {
            TimeRange(detectionSeconds.min() - before, detectionSeconds.max() + after)
        }

    fun needsPrevious(window: TimeRange): Boolean = window.start < 0.0

    fun needsNext(
        window: TimeRange,
        currentDuration: Double,
    ): Boolean = window.end > currentDuration

    /** Стыкуются ли два момента: расходятся не больше чем на [CONTIGUITY_TOLERANCE]. */
    fun contiguous(
        expected: Instant,
        actual: Instant,
    ): Boolean = Duration.between(expected, actual).abs() <= CONTIGUITY_TOLERANCE

    /**
     * Окно, обрезанное по доступной записи. [previousDuration] и [nextDuration] — длительности
     * соседей, которые стыкуются и прочитаны; `null` — соседа нет.
     */
    fun footage(
        window: TimeRange,
        currentDuration: Double,
        previousDuration: Double?,
        nextDuration: Double?,
    ): Footage {
        val earliest = previousDuration?.let { -it } ?: 0.0
        val latest = currentDuration + (nextDuration ?: 0.0)
        val start = maxOf(window.start, earliest)
        val end = minOf(window.end, latest)
        return Footage(TimeRange(start, end), missingBefore = window.start < start, missingAfter = window.end > end)
    }

    /**
     * Моменты клеток на отрезке без последних [END_GUARD_SECONDS]: [maxTiles] клеток, если шаг
     * выходит не меньше [MIN_STEP_SECONDS], иначе шаг [MIN_STEP_SECONDS]. Число клеток задаётся
     * ветками явно, а не делением обратно: в `double` `7.506 / (7.506 / 15)` равно
     * `14.999999999999998`, и `floor` потерял бы клетку. `null` — клеток меньше [MIN_TILES].
     */
    fun tileGrid(
        footage: TimeRange,
        maxTiles: Int,
    ): TileGrid? {
        val usable = footage.length - END_GUARD_SECONDS
        if (usable < 0.0) return null
        val count: Int
        val step: Double
        if (usable >= MIN_STEP_SECONDS * (maxTiles - 1)) {
            count = maxTiles
            step = usable / (maxTiles - 1)
        } else {
            count = floor(usable / MIN_STEP_SECONDS).toInt() + 1
            step = MIN_STEP_SECONDS
        }
        if (count < MIN_TILES) return null
        return TileGrid(List(count) { k -> footage.start + k * step }, step)
    }

    /** В каком файле лежит момент [seconds] шкалы записи и сколько секунд от начала этого файла. */
    fun locate(
        seconds: Double,
        currentDuration: Double,
        previousDuration: Double?,
    ): SegmentMoment =
        when {
            seconds < 0.0 -> {
                val previous = requireNotNull(previousDuration) { "moment $seconds needs the previous segment" }
                SegmentMoment(SegmentRole.PREVIOUS, (previous + seconds).coerceAtLeast(0.0))
            }

            seconds < currentDuration -> {
                SegmentMoment(SegmentRole.CURRENT, seconds)
            }

            else -> {
                SegmentMoment(SegmentRole.NEXT, seconds - currentDuration)
            }
        }

    /** Индексы клеток, ближайших к моментам детекций. */
    fun markedTiles(
        moments: List<Double>,
        detectionSeconds: List<Double>,
    ): Set<Int> =
        detectionSeconds
            .mapNotNull { detection -> moments.indices.minByOrNull { abs(moments[it] - detection) } }
            .toSet()

    fun layout(tiles: Int): GridLayout {
        require(tiles > 0) { "tiles must be positive, was $tiles" }
        val columns = ceil(sqrt(tiles.toDouble())).toInt()
        return GridLayout(columns, (tiles + columns - 1) / columns)
    }

    /** Ширина клетки: сетка всегда [GRID_WIDTH] px, ширина чётная — так спокойнее JPEG-кодеру ffmpeg. */
    fun tileWidth(columns: Int): Int = (GRID_WIDTH / columns) and 1.inv()

    /** Подпись клетки: время от начала показанного отрезка; у клетки рядом с детекцией — пометка. */
    fun tileLabel(
        seconds: Double,
        marked: Boolean,
    ): String {
        val time = String.format(Locale.US, "%.1fs", seconds)
        return if (marked) "$time • detection" else time
    }
}
