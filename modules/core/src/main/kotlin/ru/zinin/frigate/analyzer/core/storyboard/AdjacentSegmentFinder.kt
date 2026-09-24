package ru.zinin.frigate.analyzer.core.storyboard

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.video.VideoInfo
import ru.zinin.frigate.analyzer.core.video.VideoProbe
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** Соседний сегмент, который стыкуется с текущей записью и прочитан ffprobe. */
data class Segment(
    val recording: RecordingDto,
    val path: Path,
    val info: VideoInfo,
)

/**
 * Ищет соседние сегменты камеры для раскадровки. Предыдущий уже на диске — одна проверка. Следующего
 * может ещё не быть: строка появляется, когда watcher заметит файл, а файл Frigate может ещё
 * дописывать, поэтому поиск повторяется раз в секунду до срока. ffprobe соседей идёт мимо семафора
 * сборщика: он дешёвый. У предыдущего он один; следующий, который устоялся, но пока не читается,
 * пробуется на каждом опросе до срока — не чаще одного ffprobe в секунду на ждущее описание.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class AdjacentSegmentFinder(
    private val recordingEntityService: RecordingEntityService,
    private val videoProbe: VideoProbe,
    private val clock: Clock,
) {
    /** Предыдущий сегмент, если он кончается там, где начинается [current]; иначе `null`. */
    suspend fun previous(current: RecordingDto): Segment? {
        val start = current.recordTimestamp
        val candidate =
            lookup("previous", current) {
                recordingEntityService.findPreviousSegment(current.camId, start.minus(PREVIOUS_LOOKBACK), start)
            } ?: return null
        val info = probe(candidate) ?: return null
        val end = candidate.recordTimestamp.plus(durationOf(info.durationSeconds))
        if (!StoryboardPlanner.contiguous(start, end)) {
            logger.debug { "Previous segment ${candidate.id} of ${current.id} ends at $end, not at $start; not used" }
            return null
        }
        return Segment(candidate, Path.of(candidate.filePath), info)
    }

    /**
     * Следующий сегмент, если он начинается там, где кончается [current]. Проверяет раз в секунду до
     * [deadline]; срок в прошлом — ровно одна проверка. Сегмент, начавшийся с разрывом не дальше
     * [NEXT_LOOKAHEAD] от ожидаемого начала, не ждём: Frigate пропустил запись, и дальше ждать нечего.
     * Разрыв длиннее — например, целиком пропущенный сегмент (запись только по движению) — поиск от
     * опоздания не отличит: следующий файл начинается за окном запроса, и опрос идёт до срока; в строке
     * INFO сборщика это `next: missing after N s`.
     */
    suspend fun next(
        current: RecordingDto,
        currentDuration: Double,
        deadline: Instant,
    ): Segment? {
        val expectedStart = current.recordTimestamp.plus(durationOf(currentDuration))
        val until = expectedStart.plus(NEXT_LOOKAHEAD)
        while (true) {
            val candidate =
                lookup("next", current) {
                    recordingEntityService.findNextSegment(current.camId, current.recordTimestamp, until)
                }
            if (candidate != null) {
                if (!StoryboardPlanner.contiguous(expectedStart, candidate.recordTimestamp)) {
                    logger.debug {
                        "Next segment ${candidate.id} of ${current.id} starts at ${candidate.recordTimestamp}, " +
                            "not at $expectedStart; not used"
                    }
                    return null
                }
                if (settled(candidate)) {
                    probe(candidate)?.let { return Segment(candidate, Path.of(candidate.filePath), it) }
                }
            }
            if (!clock.instant().isBefore(deadline)) return null
            delay(POLL_INTERVAL)
        }
    }

    /** Сбой запроса к базе — как «соседа нет»: раскадровка без него лучше, чем никакой. */
    private suspend fun lookup(
        which: String,
        current: RecordingDto,
        query: suspend () -> RecordingDto?,
    ): RecordingDto? =
        try {
            query()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Cannot look up the $which segment of ${current.id}" }
            null
        }

    /** Файл не менялся последнюю секунду: Frigate его дописал. */
    private suspend fun settled(candidate: RecordingDto): Boolean =
        try {
            val modified = withContext(Dispatchers.IO) { Files.getLastModifiedTime(Path.of(candidate.filePath)) }
            !modified.toInstant().isAfter(clock.instant().minus(SETTLE_TIME))
        } catch (e: IOException) {
            logger.debug { "Segment ${candidate.id} is not readable yet: ${e.message}" }
            false
        }

    private suspend fun probe(candidate: RecordingDto): VideoInfo? =
        try {
            videoProbe.probe(Path.of(candidate.filePath))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug { "Cannot probe segment ${candidate.id} yet: ${e.message}" }
            null
        }

    companion object {
        val PREVIOUS_LOOKBACK: Duration = Duration.ofSeconds(60)
        val NEXT_LOOKAHEAD: Duration = Duration.ofSeconds(5)
        val SETTLE_TIME: Duration = Duration.ofSeconds(1)
        val POLL_INTERVAL = 1.seconds

        internal fun durationOf(seconds: Double): Duration = Duration.ofNanos((seconds * 1_000_000_000).toLong())
    }
}
