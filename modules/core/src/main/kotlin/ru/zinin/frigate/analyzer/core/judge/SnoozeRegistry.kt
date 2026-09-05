package ru.zinin.frigate.analyzer.core.judge

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class CameraSnooze(
    val camId: String,
    val anchor: Instant,
    val until: Instant,
    /** Класс → сколько объектов этого класса было в оценённой записи. */
    val covered: Map<String, Int>,
) {
    val minutes: Long get() = Duration.between(anchor, until).toMinutes()
}

/**
 * Snooze по камерам, только память процесса. Покрытие считается по модулю разницы времени записи и
 * якоря — бэклог разбирается от новых к старым, тот же приём, что у cooldown REAPPEARED.
 */
class SnoozeRegistry {
    private val byCamera = ConcurrentHashMap<String, CameraSnooze>()

    fun covers(
        camId: String,
        recordTimestamp: Instant,
        classes: Map<String, Int>,
    ): CameraSnooze? {
        if (classes.isEmpty()) return null
        val snooze = byCamera[camId] ?: return null
        val window = Duration.between(snooze.anchor, snooze.until)
        if (Duration.between(snooze.anchor, recordTimestamp).abs() > window) return null
        val escalated = classes.any { (cls, count) -> count > (snooze.covered[cls] ?: 0) }
        return if (escalated) null else snooze
    }

    /**
     * Вердикт кандидата, который СТАРШЕ текущего якоря камеры, не меняет ничего — ни окна, ни
     * покрытия, ни снятия при `minutes == 0`. Бэклог разбирается от новых к старым
     * (`findUnprocessedForUpdate` сортирует по `file_creation_timestamp`, а якорь берётся из
     * `record_timestamp` — колонки разные, но идут рука об руку), и вердикт по более старой записи
     * сдвинул бы окно назад или снял бы его совсем, оставив живые дубли без укрытия, ради которого
     * snooze и сделан. Сравнение якорей заодно делает результат независимым от порядка: какой из
     * двух вердиктов дойдёт до реестра первым, неважно.
     */
    fun set(
        camId: String,
        anchor: Instant,
        minutes: Int,
        classes: Map<String, Int>,
    ) {
        byCamera.compute(camId) { _, current ->
            when {
                current != null && current.anchor.isAfter(anchor) -> current
                minutes <= 0 || classes.isEmpty() -> null
                else -> CameraSnooze(camId, anchor, anchor.plus(Duration.ofMinutes(minutes.toLong())), classes.toMap())
            }
        }
    }

    fun snapshot(): List<CameraSnooze> = byCamera.values.sortedBy { it.camId }
}
