package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.dto.NewNotificationVerdict
import ru.zinin.frigate.analyzer.model.dto.VerdictCountRow
import ru.zinin.frigate.analyzer.model.persistent.NotificationVerdictEntity
import java.time.Instant

interface NotificationVerdictService {
    suspend fun record(verdict: NewNotificationVerdict): NotificationVerdictEntity

    suspend fun recentForCamera(
        camId: String,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<NotificationVerdictEntity>

    suspend fun lastPublished(camId: String): NotificationVerdictEntity?

    /** null camId = все камеры. */
    suspend fun latest(
        camId: String?,
        limit: Int,
    ): List<NotificationVerdictEntity>

    suspend fun countersSince(since: Instant): List<VerdictCountRow>
}
