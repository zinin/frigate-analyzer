package ru.zinin.frigate.analyzer.core.pipeline.frame

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class RecordingTracker {
    private val recordings = ConcurrentHashMap<UUID, RecordingState>()

    /**
     * Регистрирует запись для отслеживания прогресса
     * @param recordId ID записи
     * @param frames список кадров записи (не пустой)
     */
    fun registerRecording(
        recordId: UUID,
        frames: List<FrameData>,
    ) {
        require(frames.isNotEmpty()) { "frames must not be empty" }

        val state = RecordingState(recordId, frames)
        recordings[recordId] = state
        logger.debug { "Registered recording $recordId with ${frames.size} frames" }
    }

    /**
     * Отмечает кадр как успешно обработанный.
     * @return true если это последний кадр и текущий поток должен финализировать запись
     */
    fun markCompleted(
        recordId: UUID,
        frameIndex: Int,
        response: DetectResponse,
    ): Boolean {
        val state = getStateOrThrow(recordId)
        val shouldFinalize = state.markCompleted(frameIndex, response)

        logger.debug {
            "Recording $recordId: completed ${state.getCompletedCount()}/${state.totalFrames}, " +
                "pending ${state.getPendingCount()}"
        }

        return shouldFinalize
    }

    /**
     * Отмечает кадр как неудачно обработанный.
     * Безопасно вызывать даже если кадр уже был обработан через markCompleted.
     * @return true если это последний кадр и текущий поток должен финализировать запись
     */
    fun markFailed(
        recordId: UUID,
        frameIndex: Int,
    ): Boolean {
        val state = getStateOrThrow(recordId)

        // Если кадр уже не в pending (обработан или уже failed) - ничего не делаем
        if (!state.isPending(frameIndex)) {
            logger.debug { "Frame $frameIndex already processed, skipping markFailed" }
            return false
        }

        val shouldFinalize = state.markFailed(frameIndex)

        logger.debug {
            "Recording $recordId: failed frame $frameIndex, " +
                "completed ${state.getCompletedCount()}/${state.totalFrames}, " +
                "pending ${state.getPendingCount()}"
        }

        return shouldFinalize
    }

    /**
     * Получить состояние записи
     */
    fun getState(recordId: UUID): RecordingState? = recordings[recordId]

    /**
     * Удалить запись из отслеживания и вернуть её состояние
     */
    fun removeRecording(recordId: UUID): RecordingState? {
        val state = recordings.remove(recordId)
        if (state != null) {
            logger.debug { "Removed recording $recordId from tracker" }
        }
        return state
    }

    /**
     * Проверить, зарегистрирована ли запись
     */
    fun isRegistered(recordId: UUID): Boolean = recordings.containsKey(recordId)

    /**
     * Количество записей в обработке
     */
    fun getActiveRecordingsCount(): Int = recordings.size

    private fun getStateOrThrow(recordId: UUID): RecordingState =
        recordings[recordId]
            ?: throw IllegalStateException("Recording $recordId not registered in tracker")
}
