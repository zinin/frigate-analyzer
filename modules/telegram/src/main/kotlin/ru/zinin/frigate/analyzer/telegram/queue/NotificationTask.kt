package ru.zinin.frigate.analyzer.telegram.queue

import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import java.time.Instant
import java.util.UUID

data class NotificationTask(
    val id: UUID,
    val chatId: Long,
    val message: String,
    val visualizedFrames: List<VisualizedFrameData>,
    val createdAt: Instant = Instant.now(),
)
