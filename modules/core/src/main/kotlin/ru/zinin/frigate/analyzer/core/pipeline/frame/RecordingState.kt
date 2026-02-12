package ru.zinin.frigate.analyzer.core.pipeline.frame

import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Состояние обработки записи.
 * Отслеживает прогресс через кадры.
 */
class RecordingState(
    val recordId: UUID,
    framesList: List<FrameData>,
) {
    /** Все кадры записи */
    private val frames =
        ConcurrentHashMap<Int, FrameData>().apply {
            framesList.forEach { put(it.frameIndex, it) }
        }

    /** Индексы кадров, ожидающих обработки */
    private val pendingIndices =
        ConcurrentHashMap.newKeySet<Int>().apply {
            addAll(framesList.map { it.frameIndex })
        }

    /** Флаг финализации — гарантирует однократное выполнение */
    private val finalized = AtomicBoolean(false)

    /** Общее количество кадров */
    val totalFrames: Int = framesList.size

    /**
     * Отмечает кадр как успешно обработанный.
     * @return true если это был последний кадр и текущий поток должен финализировать запись
     */
    fun markCompleted(
        frameIndex: Int,
        response: DetectResponse,
    ): Boolean {
        if (pendingIndices.remove(frameIndex)) {
            frames.computeIfPresent(frameIndex) { _, frame ->
                frame.copy(detectResponse = response)
            }
        }
        return tryAcquireFinalization()
    }

    /**
     * Отмечает кадр как неудачно обработанный (без результата).
     * @return true если это был последний кадр и текущий поток должен финализировать запись
     */
    fun markFailed(frameIndex: Int): Boolean {
        pendingIndices.remove(frameIndex)
        return tryAcquireFinalization()
    }

    /**
     * Проверяет, ожидает ли кадр обработки
     */
    fun isPending(frameIndex: Int): Boolean = pendingIndices.contains(frameIndex)

    /**
     * Получить все успешно обработанные кадры (с detectResponse)
     */
    fun getFrames(): List<FrameData> = frames.values.filter { it.detectResponse != null }

    /**
     * Количество успешно обработанных кадров
     */
    fun getCompletedCount(): Int = frames.values.count { it.detectResponse != null }

    /**
     * Количество необработанных кадров
     */
    fun getPendingCount(): Int = pendingIndices.size

    /**
     * Количество неудачных (totalFrames - completed - pending)
     */
    fun getFailedCount(): Int = totalFrames - getCompletedCount() - pendingIndices.size

    /**
     * Проверяет завершение и атомарно захватывает право на финализацию.
     */
    private fun tryAcquireFinalization(): Boolean {
        if (pendingIndices.isEmpty()) {
            return finalized.compareAndSet(false, true)
        }
        return false
    }
}
