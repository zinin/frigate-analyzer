package ru.zinin.frigate.analyzer.telegram.queue

import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.telegram.service.model.RecordingNotificationData
import java.time.Instant
import java.util.UUID

sealed interface NotificationTask {
    val id: UUID
    val chatId: Long
    val createdAt: Instant
}

data class RecordingNotificationTask(
    override val id: UUID,
    override val chatId: Long,
    val data: RecordingNotificationData,
    val visualizedFrames: List<VisualizedFrameData>,
    /** ID of the recording, used for callback data in inline export buttons. */
    val recordingId: UUID,
    /**
     * Идентификаторы кадров, общие для всех получателей одной записи: первый отправитель
     * грузит байты, остальные ссылаются на его `file_id`.
     */
    val frameIds: SharedFrameIds,
    val language: String? = null,
    /**
     * Shared Deferred across all recipients of the same recording — one AI request
     * fans out to N edits (one per recipient). Started in
     * TelegramNotificationServiceImpl.sendRecordingNotification AFTER subscriber
     * filtering, before enqueue of each task.
     * null — feature disabled / no frames / no subscribers.
     */
    val descriptionHandle: Deferred<Result<DescriptionResult>>? = null,
    override val createdAt: Instant = Instant.now(),
) : NotificationTask

data class SimpleTextNotificationTask(
    override val id: UUID,
    override val chatId: Long,
    val text: String,
    override val createdAt: Instant = Instant.now(),
) : NotificationTask
