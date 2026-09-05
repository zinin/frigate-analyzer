package ru.zinin.frigate.analyzer.core.facade

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.core.config.DescriptionCoroutineScope
import ru.zinin.frigate.analyzer.core.judge.JudgeCandidate
import ru.zinin.frigate.analyzer.core.judge.NotificationJudgeService
import ru.zinin.frigate.analyzer.core.service.FrameVisualizationService
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import ru.zinin.frigate.analyzer.service.NotificationDecisionService
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

@Component
class RecordingProcessingFacade(
    private val recordingEntityService: RecordingEntityService,
    private val telegramNotificationService: TelegramNotificationService,
    private val frameVisualizationService: FrameVisualizationService,
    private val descriptionAgentProvider: ObjectProvider<DescriptionAgent>,
    private val descriptionScope: DescriptionCoroutineScope,
    private val descriptionProperties: DescriptionProperties,
    private val notificationDecisionService: NotificationDecisionService,
    // ObjectProvider, а не прямая зависимость: при application.ai.description.enabled=false бина
    // настроек нет вовсе, и обязательная инъекция сломала бы старт с выключенными описаниями.
    private val runtimeSettingsProvider: ObjectProvider<DescriptionRuntimeSettings>,
    // ObjectProvider: бина нет при application.ai.judge.enabled=false, и фасад тогда отправляет сам.
    private val judgeProvider: ObjectProvider<NotificationJudgeService>,
) {
    suspend fun processAndNotify(
        request: SaveProcessingResultRequest,
        failedFramesCount: Int = 0,
    ) {
        val recordingId = request.recordingId

        if (failedFramesCount > 0) {
            logger.warn {
                "Recording $recordingId has $failedFramesCount failed frames, " +
                    "skipping save (will retry automatically)"
            }
            return
        }

        // Visualize frames BEFORE saving the result.
        // If visualization fails, the recording will be reprocessed.
        val visualizedFrames = frameVisualizationService.visualizeFrames(request.frames)
        val recordingNotificationsGloballyEnabled =
            if (request.hasDetections()) {
                // Resolve the settings-backed gate before marking the recording processed.
                // A transient settings failure should leave the recording retryable.
                notificationDecisionService.isRecordingNotificationsGloballyEnabled()
            } else {
                null
            }

        val savedResult =
            try {
                recordingEntityService.saveProcessingResult(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to save processing result for recording $recordingId" }
                throw e
            }

        val recording = savedResult.recording
        val decision =
            notificationDecisionService.evaluate(
                recording,
                savedResult.detections,
                recordingNotificationsGloballyEnabled,
            )
        if (!decision.shouldNotify) {
            logger.debug {
                "Notification suppressed for recording=$recordingId reason=${decision.reason}"
            }
            return
        }
        // Build supplier for lazy describe-job kick-off; invoked by Telegram layer
        // AFTER subscriber filtering so AI tokens are not wasted on zero-recipient recordings.
        val descriptionSupplier = buildDescriptionSupplier(recordingId, request)
        val judge = judgeProvider.getIfAvailable()
        if (judge != null) {
            // Судья держит кандидата на время ответа модели, поэтому уходит в свой scope: consumer
            // pipeline возвращается к кадрам сразу. Отправка при PUBLISH — внутри судьи, тем же
            // supplier-ом описания, что построен выше.
            judge.submit(
                JudgeCandidate(
                    recording = recording,
                    detections = savedResult.detections,
                    decision = decision,
                    frames = request.frames,
                    visualizedFrames = visualizedFrames,
                    descriptionSupplier = descriptionSupplier,
                ),
            )
            return
        }
        try {
            telegramNotificationService.sendRecordingNotification(
                recording,
                visualizedFrames,
                descriptionSupplier,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to send telegram notification for recording $recordingId" }
        }
    }

    private fun SaveProcessingResultRequest.hasDetections(): Boolean =
        frames.any { frame -> frame.detectResponse?.detections?.isNotEmpty() == true }

    /**
     * Runtime AI-description switch, read fail-open to `true` — verbatim after
     * `TelegramNotificationServiceImpl.signalNotificationsGloballyEnabled`.
     *
     * Both call sites sit AFTER `saveProcessingResult`, so throwing here would lose the
     * notification with no retry: the recording is already marked processed. The global
     * notification flag is read before saving for the opposite reason — it decides whether to
     * notify at all — while this switch only decides whether to enrich the notification, so an
     * unreadable key is treated exactly like an absent one, whose default is `true`.
     */
    private suspend fun descriptionsEnabled(recordingId: UUID): Boolean =
        try {
            withTimeout(SETTINGS_READ_TIMEOUT) {
                runtimeSettingsProvider.getIfAvailable()?.descriptionsEnabled() ?: true
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn {
                "Reading the AI description switch for $recordingId timed out after $SETTINGS_READ_TIMEOUT; failing open"
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read the AI description switch for $recordingId; failing open" }
            true
        }

    /**
     * Returns a supplier that lazily kicks off a describe-job. Returns null when the agent is
     * absent (feature disabled / provider mismatch), when the runtime switch is off, OR when no
     * frames with detections are available.
     * When non-null, the supplier returns a non-null Deferred when invoked — the rate limiter
     * has already consumed a slot at the call site, and a null return would silently waste it.
     */
    private suspend fun buildDescriptionSupplier(
        recordingId: UUID,
        request: SaveProcessingResultRequest,
    ): (() -> Deferred<Result<DescriptionResult>>)? {
        val agent = descriptionAgentProvider.getIfAvailable() ?: return null
        // Рантайм-выключатель: то же поведение, что «агента нет» — уведомление уходит с
        // DescriptionState.Absent, плейсхолдеров нет, слот rate limiter не тратится, потому что
        // TelegramNotificationServiceImpl отсекает null-supplier ДО tryAcquire().
        if (!descriptionsEnabled(recordingId)) {
            logger.debug { "AI descriptions switched off at runtime; skipping describe-job for $recordingId" }
            return null
        }

        val common = descriptionProperties.common
        // Mirror FrameVisualizationService ranking (confidence, then detection count) so Claude
        // sees the exact subset the user receives in the notification's collage. Capped by the visualization
        // limit so the AI set is always contained in the user-visible set — see `selectTopFrames`.
        val cap = minOf(common.maxFrames, frameVisualizationService.maxFrames)
        val trimmedFrames =
            frameVisualizationService
                .selectTopFrames(request.frames, cap)
                .sortedBy { it.frameIndex } // chronological order in the prompt, post-selection
                .map { DescriptionRequest.FrameImage(it.frameIndex, it.frameBytes) }

        if (trimmedFrames.isEmpty()) {
            logger.debug { "No frames with detections for recording $recordingId; skipping describe-job" }
            return null
        }

        val descriptionRequest =
            DescriptionRequest(
                recordingId = recordingId,
                frames = trimmedFrames,
                language = common.language,
                shortMaxLength = common.shortMaxLength,
                detailedMaxLength = common.detailedMaxLength,
            )

        return {
            descriptionScope.async {
                // Вторая проверка выключателя, вплотную к вызову модели: между сборкой supplier-а и
                // его вызовом лежат фильтрация получателей и rate limiter, поэтому одной проверки
                // хватало бы лишь на «подействует со следующей записи», а кнопку жмут тогда, когда
                // что-то идёт не так прямо сейчас. Цена — поиск в процессном кэше AppSettingsService
                // на пути, который вот-вот потратит секунды и деньги на вызов модели.
                if (!descriptionsEnabled(recordingId)) {
                    logger.debug { "AI descriptions switched off at runtime; skipping describe-call for $recordingId" }
                    // Не отмена и не null: слот лимитера уже потрачен, сообщение уже ушло с
                    // плейсхолдером, и заменить его умеет только завершившийся Deferred —
                    // Result.failure даёт DescriptionState.Failed, отменённый await() не дал бы
                    // ничего, оставив плейсхолдер навсегда.
                    return@async Result.failure(IllegalStateException("AI descriptions are switched off at runtime"))
                }
                try {
                    Result.success(agent.describe(descriptionRequest))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Without this, the exception is swallowed into Result.failure and the user
                    // sees only the localized "Описание недоступно" fallback in Telegram with
                    // nothing in the logs explaining why — making AI failures invisible to ops.
                    logger.warn(e) { "AI description failed for recording $recordingId; users will see fallback" }
                    Result.failure(e)
                }
            }
        }
    }

    private companion object {
        val SETTINGS_READ_TIMEOUT = 5.seconds
    }
}
