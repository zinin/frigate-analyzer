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

    fun set(
        camId: String,
        anchor: Instant,
        minutes: Int,
        classes: Map<String, Int>,
    ) {
        if (minutes <= 0 || classes.isEmpty()) {
            byCamera.remove(camId)
            return
        }
        byCamera[camId] = CameraSnooze(camId, anchor, anchor.plus(Duration.ofMinutes(minutes.toLong())), classes.toMap())
    }

    fun clear(camId: String) {
        byCamera.remove(camId)
    }

    fun snapshot(): List<CameraSnooze> = byCamera.values.sortedBy { it.camId }
}
