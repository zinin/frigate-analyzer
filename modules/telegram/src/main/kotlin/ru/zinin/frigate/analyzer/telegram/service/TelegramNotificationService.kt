package ru.zinin.frigate.analyzer.telegram.service

import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import java.time.Duration
import java.time.Instant

interface TelegramNotificationService {
    suspend fun sendRecordingNotification(
        recording: RecordingDto,
        visualizedFrames: List<VisualizedFrameData>,
        /**
         * Supplier lazily starts the describe-job only after the Telegram layer
         * confirms at least one subscriber will receive the notification. Otherwise
         * AI tokens would be wasted on recordings with no recipients.
         *
         * null — feature is disabled or there are no frames. When non-null, the
         * supplier MUST return a non-null Deferred when invoked: the rate limiter
         * has already consumed a slot at the call site, and a null return would
         * silently waste it.
         */
        descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>)? = null,
    )

    /**
     * `true` when at least one authorized subscriber still has recording notifications on.
     *
     * The same filter [sendRecordingNotification] applies, exposed so a caller can find out
     * *before* paying for work whose only output is that call. The judge asks it for exactly the
     * reason the `descriptionSupplier` above is lazy: a recording nobody will receive must not
     * spend a model call or a rate-limit slot.
     */
    suspend fun hasRecordingRecipients(): Boolean

    /** Notify all active subscribers that camera [camId] has stopped writing recordings. */
    suspend fun sendCameraSignalLost(
        camId: String,
        lastSeenAt: Instant,
        now: Instant,
    )

    /** Notify all active subscribers that camera [camId] is writing recordings again. */
    suspend fun sendCameraSignalRecovered(
        camId: String,
        downtime: Duration,
    )

    /**
     * Send a localized text message to the bot owner (defined by application.telegram.owner).
     * The [textBuilder] receives the owner's resolved language code ("en" when the owner has
     * no stored languageCode) and returns the already-localized text to send. Doing the owner
     * lookup once inside this service avoids a second DB round-trip at the call site.
     * No-op when telegram is disabled or the owner has not activated the bot yet (in those
     * cases [textBuilder] is not invoked).
     */
    suspend fun sendOwnerMessage(textBuilder: (language: String) -> String)
}
