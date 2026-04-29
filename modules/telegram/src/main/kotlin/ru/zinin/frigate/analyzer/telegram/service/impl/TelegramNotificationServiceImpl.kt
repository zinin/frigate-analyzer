package ru.zinin.frigate.analyzer.telegram.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.queue.RecordingNotificationTask
import ru.zinin.frigate.analyzer.telegram.queue.SimpleTextNotificationTask
import ru.zinin.frigate.analyzer.telegram.queue.TelegramNotificationQueue
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val logger = KotlinLogging.logger {}

@Service
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramNotificationServiceImpl(
    private val userService: TelegramUserService,
    private val notificationQueue: TelegramNotificationQueue,
    private val uuidGeneratorHelper: UUIDGeneratorHelper,
    private val msg: MessageResolver,
    private val signalLossFormatter: SignalLossMessageFormatter,
    private val rateLimiterProvider: ObjectProvider<DescriptionRateLimiter>,
    private val appSettings: AppSettingsService,
) : TelegramNotificationService {
    override suspend fun sendRecordingNotification(
        recording: RecordingDto,
        visualizedFrames: List<VisualizedFrameData>,
        descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>)?,
    ) {
        if (recording.detectionsCount == 0) {
            logger.debug { "No detections found, skipping notification for ${recording.filePath}" }
            return
        }

        val usersWithZones = userService.getAuthorizedUsersWithZones()
        if (usersWithZones.isEmpty()) {
            logger.debug { "No active subscribers found, skipping notification" }
            return
        }

        val recipients = usersWithZones.filter { it.notificationsRecordingEnabled }
        if (recipients.isEmpty()) {
            logger.debug { "No subscribers with recording-notifications enabled" }
            return
        }

        // Lazy start of describe-job: invoked ONCE, shared across all recipients of the same recording.
        // Rate limiter (when AI description is enabled) gates the invocation: if the sliding-window
        // limit is exceeded, the recording goes out as a plain notification — no placeholder, no
        // second message, no edit job, no Claude call. ObjectProvider is used because the limiter
        // bean only exists when application.ai.description.enabled=true.
        //
        // When visualizedFrames is empty, TelegramNotificationSender takes the no-photo branch
        // and would cancel descriptionHandle anyway. Short-circuiting here avoids a wasted slot
        // in the rate limiter (defensive against future drift between visualizeFrames and
        // selectTopFrames filters in the facade).
        val descriptionHandle =
            when {
                descriptionSupplier == null || visualizedFrames.isEmpty() -> {
                    null
                }

                else -> {
                    val limiter = rateLimiterProvider.getIfAvailable()
                    when {
                        limiter == null -> {
                            descriptionSupplier.invoke()
                        }

                        limiter.tryAcquire() -> {
                            descriptionSupplier.invoke()
                        }

                        else -> {
                            logger.warn {
                                "AI description rate limit reached, skipping description for recording " +
                                    "${recording.id} (cam=${recording.camId})"
                            }
                            null
                        }
                    }
                }
            }

        recipients.forEach { userZone ->
            val lang = userZone.language ?: "en"
            val message = formatRecordingMessage(recording, userZone.zone, lang)
            val task =
                RecordingNotificationTask(
                    id = uuidGeneratorHelper.generateV1(),
                    chatId = userZone.chatId,
                    message = message,
                    visualizedFrames = visualizedFrames,
                    recordingId = recording.id,
                    language = userZone.language,
                    descriptionHandle = descriptionHandle,
                )
            notificationQueue.enqueue(task)
        }

        logger.debug { "Enqueued notification for ${recipients.size} subscribers" }
    }

    private fun formatRecordingMessage(
        recording: RecordingDto,
        zone: ZoneId,
        language: String,
    ): String {
        val fileName = recording.filePath.substringAfterLast("/")
        val camId = recording.camId
        val detectionsCount = recording.detectionsCount
        val analyzedFrames = recording.analyzedFramesCount
        val analyzeTime = recording.analyzeTime

        val formatter =
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.LONG)
                .withLocale(Locale.forLanguageTag(language))

        val timestampFormatted =
            recording.processTimestamp
                ?.atZone(zone)
                ?.format(formatter)
                ?: "N/A"

        val recordTimestampFormatted =
            recording.recordTimestamp
                .atZone(zone)
                .format(formatter)

        return buildString {
            appendLine(msg.get("notification.recording.title", language))
            appendLine()
            appendLine(msg.get("notification.recording.camera", language, camId))
            appendLine(msg.get("notification.recording.file", language, fileName))
            appendLine(msg.get("notification.recording.detections", language, detectionsCount))
            appendLine(msg.get("notification.recording.frames", language, analyzedFrames))
            appendLine(msg.get("notification.recording.processing.time", language, analyzeTime))
            appendLine(msg.get("notification.recording.timestamp", language, recordTimestampFormatted))
            appendLine(msg.get("notification.recording.processed", language, timestampFormatted))
        }
    }

    override suspend fun sendCameraSignalLost(
        camId: String,
        lastSeenAt: Instant,
        now: Instant,
    ) {
        if (!signalNotificationsGloballyEnabled(camId, eventType = "loss")) {
            logger.debug { "Signal-loss notifications globally disabled — skipping cam=$camId" }
            return
        }
        val usersWithZones = userService.getAuthorizedUsersWithZones()
        if (usersWithZones.isEmpty()) {
            logger.debug { "No active subscribers for signal-loss alert (cam=$camId)" }
            return
        }
        val recipients = usersWithZones.filter { it.notificationsSignalEnabled }
        if (recipients.isEmpty()) {
            logger.debug { "No subscribers with signal-loss notifications enabled (cam=$camId)" }
            return
        }
        recipients.forEach { userZone ->
            val lang = userZone.language ?: "en"
            val text =
                signalLossFormatter.buildLossMessage(
                    camId = camId,
                    lastSeenAt = lastSeenAt,
                    now = now,
                    zone = userZone.zone,
                    language = lang,
                )
            notificationQueue.enqueue(
                SimpleTextNotificationTask(
                    id = uuidGeneratorHelper.generateV1(),
                    chatId = userZone.chatId,
                    text = text,
                ),
            )
        }
        logger.info { "Enqueued signal-loss alert for camera $camId to ${recipients.size} recipients" }
    }

    override suspend fun sendCameraSignalRecovered(
        camId: String,
        downtime: Duration,
    ) {
        if (!signalNotificationsGloballyEnabled(camId, eventType = "recovery")) {
            logger.debug { "Signal-recovery notifications globally disabled — skipping cam=$camId" }
            return
        }
        val usersWithZones = userService.getAuthorizedUsersWithZones()
        if (usersWithZones.isEmpty()) {
            logger.debug { "No active subscribers for signal-recovery alert (cam=$camId)" }
            return
        }
        val recipients = usersWithZones.filter { it.notificationsSignalEnabled }
        if (recipients.isEmpty()) {
            logger.debug { "No subscribers with signal-recovery notifications enabled (cam=$camId)" }
            return
        }
        recipients.forEach { userZone ->
            val lang = userZone.language ?: "en"
            val text =
                signalLossFormatter.buildRecoveryMessage(
                    camId = camId,
                    downtime = downtime,
                    language = lang,
                )
            notificationQueue.enqueue(
                SimpleTextNotificationTask(
                    id = uuidGeneratorHelper.generateV1(),
                    chatId = userZone.chatId,
                    text = text,
                ),
            )
        }
        logger.info { "Enqueued signal-recovery alert for camera $camId to ${recipients.size} recipients" }
    }

    private suspend fun signalNotificationsGloballyEnabled(
        camId: String,
        eventType: String,
    ): Boolean =
        try {
            appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, default = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) {
                "Failed to read global signal-notification setting for $eventType cam=$camId; failing open"
            }
            true
        }
}
