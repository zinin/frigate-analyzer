package ru.zinin.frigate.analyzer.core.storyboard

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import java.time.Duration
import java.util.Locale
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

private val logger = KotlinLogging.logger {}

/**
 * Раскадровка одного события на двух потребителей — judge и описание. При создании ничего не строит и ничего не
 * запускает: сетку собирает первый [get], остальные получают тот же результат — и готовую сетку, и «сетки нет»,
 * и «бюджет истёк». Отмена вызывающего — не результат: она пробрасывается и не запоминается, и следующий живой
 * потребитель соберёт сетку заново. Гарантия одной сборки относится к завершённой попытке, а не к прерванной.
 *
 * Держит только метаданные кадров с детекциями, без JPEG: объект живёт, пока живы кандидат judge и замыкание
 * описания.
 */
class RecordingStoryboard(
    private val recording: RecordingDto,
    detectionFrames: List<FrameData>,
    private val builder: StoryboardBuilder,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** Свой список, сразу и без байтов: исходный, с полноразмерными JPEG, не удерживаем. */
    private val frames: List<FrameData> =
        detectionFrames
            .filter { it.detectResponse?.detections?.isNotEmpty() == true }
            .map { it.copy(frameBytes = ByteArray(0)) }
    private val mutex = Mutex()

    /** Читается и пишется только под [mutex]: он же даёт видимость между потоками. */
    private var prepared: StoryboardPreparation? = null

    /**
     * Первый вызов собирает раскадровку, остальные ждут его под mutex и получают тот же объект.
     *
     * [timeout] — бюджет этой первой сборки со всеми ожиданиями builder-а: permits, соседи, ffprobe, ffmpeg;
     * `null` — без бюджета, нулевой или отрицательный — сразу TIMEOUT без сборки. [waitForNextSegment] уходит в
     * builder: `false` — следующий сегмент проверяется один раз, без ожидания. У готового результата ни бюджет, ни
     * флаг следующих вызовов ничего не меняют.
     */
    suspend fun get(
        timeout: Duration? = null,
        waitForNextSegment: Boolean = true,
    ): StoryboardPreparation =
        mutex.withLock {
            prepared ?: prepare(timeout, waitForNextSegment).also { prepared = it }
        }

    private suspend fun prepare(
        timeout: Duration?,
        waitForNextSegment: Boolean,
    ): StoryboardPreparation {
        val started = timeSource.markNow()
        val built =
            if (timeout == null) {
                builder.build(recording, frames, waitForNextSegment)
            } else {
                // Обёртка отличает null builder-а («сетки нет») от null самого withTimeoutOrNull («бюджет истёк»).
                // Чужой TimeoutCancellationException — например, timeout родителя — withTimeoutOrNull не глотает.
                val outcome =
                    withTimeoutOrNull(timeout.toKotlinDuration()) { Outcome(builder.build(recording, frames, waitForNextSegment)) }
                        ?: return timedOut(timeout, started.elapsedNow().toJavaDuration())
                outcome.built
            }
        val elapsed = started.elapsedNow().toJavaDuration()
        return if (built != null) {
            StoryboardPreparation(StoryboardPreparation.Status.AVAILABLE, built, elapsed)
        } else {
            StoryboardPreparation(StoryboardPreparation.Status.UNAVAILABLE, null, elapsed)
        }
    }

    private fun timedOut(
        budget: Duration,
        elapsed: Duration,
    ): StoryboardPreparation {
        // Отменённый builder ничего не пишет, а его INFO выходит только при успехе: без этой строки причины частых
        // timeout не измерить.
        logger.warn {
            "Storyboard for ${recording.id}: not ready within the ${fmt(budget)} s budget, gave up after ${fmt(elapsed)} s; " +
                "using the frames alone"
        }
        return StoryboardPreparation(StoryboardPreparation.Status.TIMEOUT, null, elapsed)
    }

    private fun fmt(value: Duration): String = String.format(Locale.US, "%.1f", value.toMillis() / 1000.0)

    /** Ответ builder-а, null в том числе, в ненулевой обёртке. */
    private class Outcome(
        val built: BuiltStoryboard?,
    )
}
