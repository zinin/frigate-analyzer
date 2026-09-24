package ru.zinin.frigate.analyzer.core.storyboard

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.core.config.properties.StoryboardProperties
import ru.zinin.frigate.analyzer.core.video.VideoProbe
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.Locale
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

/** Готовая раскадровка и где у неё ноль: секунды от начала записи (с предыдущим сегментом — меньше нуля). */
data class BuiltStoryboard(
    val storyboard: DescriptionRequest.Storyboard,
    val zeroSeconds: Double,
)

/**
 * Строит раскадровку для описания: окно вокруг детекций, соседние сегменты, нарезка ffmpeg-ом, сетка.
 * Любой сбой, кроме отмены, — WARN и `null`: описание уйдёт по одним кадрам.
 *
 * Семафор ограничивает только ffprobe текущей записи и запуски ffmpeg; поиск соседей, их ffprobe и
 * ожидание следующего сегмента идут без него, иначе две записи, ждущие по 30 с, задержали бы третью,
 * которой ждать нечего.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class StoryboardBuilder(
    private val properties: StoryboardProperties,
    private val videoProbe: VideoProbe,
    private val finder: AdjacentSegmentFinder,
    private val sampler: StoryboardFrameSampler,
    private val composer: StoryboardComposer,
    private val clock: Clock,
) {
    private val permits = Semaphore(MAX_CONCURRENT_BUILDS)

    /** [detectionFrames] — кадры записи с детекциями; байты не нужны, только время и детекции. */
    suspend fun build(
        recording: RecordingDto,
        detectionFrames: List<FrameData>,
    ): BuiltStoryboard? {
        val startedAt = clock.instant()
        val started = TimeSource.Monotonic.markNow()
        return try {
            assemble(recording, detectionFrames, startedAt, started)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, а не Exception: Error — OOM на большой сетке, LinkageError в Java2D — вышел бы из
            // сборщика и погубил бы всё описание, а не одну раскадровку.
            logger.warn(e) { "Storyboard for ${recording.id} failed; describing from the frames alone" }
            null
        }
    }

    /**
     * Срок ожидания следующего сегмента: от более раннего из двух моментов — начала задачи и ожидаемого
     * появления следующего файла (текущий появился в `fileCreationTimestamp`, следующий придёт через
     * длительность сегмента) — плюс `next-segment-wait`. Пайплайн берёт запись через 30 с после
     * появления файла, поэтому обычно раньше наступает ожидаемое появление; у записи из очереди оба
     * момента в прошлом, и поиск делает ровно одну проверку.
     */
    internal fun nextDeadline(
        recording: RecordingDto,
        currentDuration: Double,
        startedAt: Instant,
    ): Instant {
        val expected = recording.fileCreationTimestamp.plus(AdjacentSegmentFinder.durationOf(currentDuration))
        return minOf(startedAt, expected).plus(properties.nextSegmentWait)
    }

    private suspend fun assemble(
        recording: RecordingDto,
        detectionFrames: List<FrameData>,
        startedAt: Instant,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): BuiltStoryboard? {
        val currentPath = Path.of(recording.filePath)
        val duration = permits.withPermit { videoProbe.probe(currentPath) }.durationSeconds
        val detectionSeconds = detectionFrames.mapNotNull { it.offsetSeconds }
        val window =
            StoryboardPlanner.window(
                detectionSeconds,
                properties.before.secondsAsDouble(),
                properties.after.secondsAsDouble(),
                duration,
            )

        val previousWanted = StoryboardPlanner.needsPrevious(window)
        val nextWanted = StoryboardPlanner.needsNext(window, duration)
        val previous = if (previousWanted) finder.previous(recording) else null
        val waitStarted = TimeSource.Monotonic.markNow()
        val next = if (nextWanted) finder.next(recording, duration, nextDeadline(recording, duration, startedAt)) else null
        val waited = waitStarted.elapsedNow()

        val footage = StoryboardPlanner.footage(window, duration, previous?.info?.durationSeconds, next?.info?.durationSeconds)
        val grid = StoryboardPlanner.tileGrid(footage.range, properties.tiles)
        if (grid == null) {
            logger.warn {
                "Storyboard for ${recording.id}: ${fmt(footage.range.length)} s of footage is too little; " +
                    "describing from the frames alone"
            }
            return null
        }
        val layout = StoryboardPlanner.layout(grid.moments.size)
        val tileWidth = StoryboardPlanner.tileWidth(layout.columns)
        val files = mapOf(SegmentRole.PREVIOUS to previous?.path, SegmentRole.CURRENT to currentPath, SegmentRole.NEXT to next?.path)

        // Что на сетке, решают пришедшие клетки: failed — нарезанные соседи, не давшие ни одной (исключение или
        // пустой ответ), shown — сегменты, давшие хоть одну.
        val failed = mutableSetOf<SegmentRole>()
        val shown = mutableSetOf<SegmentRole>()
        val sampled = mutableListOf<Pair<Double, ByteArray>>()
        // Последний сегмент, давший клетки, отдал меньше, чем просили (файл кончился раньше, чем обещал ffprobe):
        // сетка кончается на его последней клетке.
        var tailCut = false
        val groups =
            grid.moments
                .map { moment -> moment to StoryboardPlanner.locate(moment, duration, previous?.info?.durationSeconds) }
                .groupBy { (_, located) -> located.role }
        // Без текущей записи раскадровки нет. Ни одна клетка не попадает в неё, когда запись короче шага клеток
        // или когда `after = 0` встречает детекцию в самом начале записи.
        if (SegmentRole.CURRENT !in groups) {
            logger.warn {
                "Storyboard for ${recording.id}: no tile falls into the current recording; describing from the frames alone"
            }
            return null
        }
        for ((role, group) in groups) {
            val file = requireNotNull(files[role]) { "no file for the $role segment" }
            val images =
                try {
                    permits.withPermit {
                        sampler.sample(file, group.first().second.localSeconds, grid.stepSeconds, group.size, tileWidth)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Без текущей записи раскадровки нет; без соседа есть, только короче.
                    if (role == SegmentRole.CURRENT) throw e
                    logger.warn(e) { "Storyboard for ${recording.id}: cannot sample the ${role.name.lowercase()} segment; leaving it out" }
                    failed += role
                    continue
                }
            // Не отдав ни кадра, ffmpeg не всегда выходит с ошибкой — это зависит от сборки. Пустой ответ — такой
            // же сбой, и правило то же: без текущей записи — запасной путь, сосед выпадает.
            if (images.isEmpty()) {
                if (role == SegmentRole.CURRENT) error("ffmpeg returned no frames of the current recording")
                logger.warn {
                    "Storyboard for ${recording.id}: ffmpeg returned no frames of the ${role.name.lowercase()} segment; leaving it out"
                }
                failed += role
                continue
            }
            shown += role
            tailCut = images.size < group.size
            group.zip(images).forEach { (entry, image) -> sampled += entry.first to image }
        }
        if (sampled.size < StoryboardPlanner.MIN_TILES) {
            logger.warn {
                "Storyboard for ${recording.id}: ffmpeg returned ${sampled.size} frames, too few; describing from the frames alone"
            }
            return null
        }

        val previousFailed = SegmentRole.PREVIOUS in failed
        val nextFailed = SegmentRole.NEXT in failed
        // Ноль — начало показанного отрезка. Без клеток предыдущего сегмента отрезок, а с ним и ноль, начинается
        // с текущей записи: иначе подписи клеток считались бы от начала, которого на сетке нет, а длина отрезка
        // включала бы непоказанный хвост предыдущего. Без клеток следующего отрезок кончается на конце записи:
        // этот момент и назовёт «Footage after … is not available». Если последний сегмент на сетке отдал меньше
        // клеток, чем просили, отрезок кончается на его последней клетке, и «Footage after …» назовёт этот момент.
        val zero = if (previousFailed) 0.0 else footage.range.start
        val shownEnd =
            when {
                tailCut -> sampled.last().first
                nextFailed -> minOf(footage.range.end, duration)
                else -> footage.range.end
            }
        val marked = StoryboardPlanner.markedTiles(sampled.map { it.first }, detectionSeconds)
        val tiles =
            sampled.mapIndexed { index, (moment, image) ->
                StoryboardComposer.Tile(image, StoryboardPlanner.tileLabel(moment - zero, index in marked), index in marked)
            }
        // Столбцы — по плану: под их ширину ffmpeg уже нарезал клетки. Ряды — по пришедшим клеткам, чтобы
        // выпавший сосед или рано кончившийся файл не оставили целых чёрных рядов: пустые ячейки бывают
        // только в последнем.
        val rows = (tiles.size + layout.columns - 1) / layout.columns
        val image = withContext(Dispatchers.Default) { composer.compose(tiles, layout.copy(rows = rows)) }
        val storyboard =
            DescriptionRequest.Storyboard(
                image = image,
                tiles = tiles.size,
                stepSeconds = grid.stepSeconds,
                durationSeconds = shownEnd - zero,
                detections = detectionMarks(detectionFrames, zero),
                missingBefore = footage.missingBefore || previousFailed,
                missingAfter = footage.missingAfter || nextFailed || tailCut,
            )
        logger.info {
            // Строка описывает то, что на сетке: в скобках только сегменты, давшие хоть одну клетку. Соседа, которого
            // не удалось нарезать, там нет, как и найденного следующего, в который не попал ни один момент.
            val parts =
                listOfNotNull(
                    "prev".takeIf { SegmentRole.PREVIOUS in shown },
                    "current".takeIf { SegmentRole.CURRENT in shown },
                    "next".takeIf { SegmentRole.NEXT in shown },
                )
            val missing =
                buildString {
                    if (previousWanted && previous == null) append(", prev: missing")
                    if (previousFailed) append(", prev: sampling failed")
                    if (nextWanted && next != null) append(", waited ${fmt(waited)} s for the next segment")
                    if (nextWanted && next == null) append(", next: missing after ${fmt(waited)} s")
                    if (nextFailed) append(", next: sampling failed")
                }
            "Storyboard for ${recording.id}: footage ${fmt(zero)}..${fmt(shownEnd)} s of the recording " +
                "(${parts.joinToString("+")}), ${tiles.size} tiles ${fmt(grid.stepSeconds)} s apart$missing, " +
                "built in ${fmt(started.elapsedNow())} s"
        }
        return BuiltStoryboard(storyboard, zero)
    }

    /** По кадру: каждый класс с лучшим confidence этого класса на кадре; время — от нуля раскадровки. */
    private fun detectionMarks(
        frames: List<FrameData>,
        zero: Double,
    ): List<DescriptionRequest.DetectionMark> =
        frames.sortedBy { it.frameIndex }.flatMap { frame ->
            frame.detectResponse
                ?.detections
                .orEmpty()
                .groupBy { it.className }
                .map { (className, detections) ->
                    DescriptionRequest.DetectionMark(className, detections.maxOf { it.confidence }, frame.offsetSeconds?.minus(zero))
                }
        }

    /** Не `toSeconds()`: член `Duration.toSeconds(): Long` выиграл бы у расширения с тем же именем. */
    private fun java.time.Duration.secondsAsDouble(): Double = toMillis() / 1000.0

    private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun fmt(value: kotlin.time.Duration): String = fmt(value.inWholeMilliseconds / 1000.0)

    private companion object {
        const val MAX_CONCURRENT_BUILDS = 2
    }
}
