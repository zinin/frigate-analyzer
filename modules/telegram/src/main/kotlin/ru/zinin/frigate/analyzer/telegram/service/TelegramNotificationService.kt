package ru.zinin.frigate.analyzer.telegram.service

import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData

interface TelegramNotificationService {
    suspend fun sendRecordingNotification(
        recording: RecordingDto,
        visualizedFrames: List<VisualizedFrameData>,
        /**
         * Supplier lazily starts the describe-job only after the Telegram layer
         * confirms at least one subscriber will receive the notification. Otherwise
         * AI tokens would be wasted on recordings with no recipients.
         *
         * null — feature is disabled or there are no frames (supplier itself may also
         * return null if it decides not to start at invocation time).
         */
        descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>?)? = null,
    )
}
