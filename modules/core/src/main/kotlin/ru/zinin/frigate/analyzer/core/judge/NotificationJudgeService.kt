package ru.zinin.frigate.analyzer.core.judge

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeAgent
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.JudgeVerdict
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import ru.zinin.frigate.analyzer.ai.description.ratelimit.JudgeRateLimiter
import ru.zinin.frigate.analyzer.model.dto.NewNotificationVerdict
import ru.zinin.frigate.analyzer.model.dto.VerdictDecision
import ru.zinin.frigate.analyzer.model.dto.VerdictReason
import ru.zinin.frigate.analyzer.model.dto.VerdictStage
import ru.zinin.frigate.analyzer.service.NotificationVerdictService
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import ru.zinin.frigate.analyzer.service.helper.BboxClusteringHelper
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class NotificationJudgeService(
    private val agentProvider: ObjectProvider<JudgeAgent>,
    private val runtimeSettings: JudgeRuntimeSettings,
    private val contextBuilder: JudgeContextBuilder,
    private val zoneResolver: JudgeZoneResolver,
    private val verdicts: NotificationVerdictService,
    private val rateLimiterProvider: ObjectProvider<JudgeRateLimiter>,
    private val telegram: TelegramNotificationService,
    private val properties: JudgeProperties,
    private val trackerProperties: ObjectTrackerProperties,
    private val descriptionProperties: DescriptionProperties,
    private val scope: JudgeCoroutineScope,
    private val clock: Clock,
) {
    private val snoozes = SnoozeRegistry()
    private val perCameraMutex = ConcurrentHashMap<String, Mutex>()
    private val queued = ConcurrentHashMap<String, AtomicInteger>()

    /** Точка входа фасада: возвращается сразу, работа идёт в [JudgeCoroutineScope]. */
    fun submit(candidate: JudgeCandidate): Job = scope.launch { process(candidate) }

    fun snapshotSnoozes(): List<CameraSnooze> = snoozes.snapshot()

    internal suspend fun process(candidate: JudgeCandidate) {
        val camId = candidate.recording.camId
        val waiting = queued.computeIfAbsent(camId) { AtomicInteger() }
        val depth = waiting.incrementAndGet()
        if (depth == QUEUE_WARN_THRESHOLD + 1) {
            logger.warn { "Judge queue for cam=$camId holds $depth candidates; the model is slower than the camera" }
        }
        try {
            if (depth > QUEUE_WARN_THRESHOLD) {
                logger.warn {
                    "Judge queue for cam=$camId is full (depth=$depth); sending recording=${candidate.recording.id} unjudged"
                }
                record(unexpectedFailover(candidate, IllegalStateException("judge queue depth $depth")))
                send(candidate)
                return
            }
            perCameraMutex.computeIfAbsent(camId) { Mutex() }.withLock { judgeLocked(candidate) }
        } catch (e: CancellationException) {
            // Фасад уже пометил запись обработанной. Без отправки под NonCancellable docker stop
            // (cancelAndJoin 10 с) молча теряет очередь камеры: пайплайн запись не повторит.
            withContext(NonCancellable) {
                logger.warn {
                    "Judge cancelled for recording=${candidate.recording.id} cam=$camId; sending unjudged"
                }
                record(unexpectedFailover(candidate, e))
                send(candidate)
            }
            throw e
        } catch (e: Exception) {
            logger.warn(e) {
                "Judge failed unexpectedly for recording=${candidate.recording.id} cam=$camId; sending unjudged"
            }
            record(unexpectedFailover(candidate, e))
            send(candidate)
        } finally {
            waiting.decrementAndGet()
        }
    }

    private suspend fun judgeLocked(candidate: JudgeCandidate) {
        val recording = candidate.recording
        val objects =
            BboxClusteringHelper.cluster(
                candidate.detections,
                trackerProperties.innerIou,
                trackerProperties.confidenceFloor,
            )
        val classCounts = objects.groupingBy { it.className }.eachCount().toSortedMap()
        val classes = classCounts.entries.joinToString(",") { "${it.key}:${it.value}" }
        val base = { stage: VerdictStage, decision: VerdictDecision, reason: VerdictReason ->
            NewNotificationVerdict(
                recording.id,
                recording.camId,
                recording.recordTimestamp,
                stage,
                decision,
                reason,
                candidate.decision.reason.name,
                classes,
            )
        }

        if (!judgeEnabled(recording.id)) {
            record(base(VerdictStage.BYPASS, VerdictDecision.PUBLISH, VerdictReason.JUDGE_OFF))
            send(candidate)
            return
        }
        val snooze = snoozes.covers(recording.camId, recording.recordTimestamp, classCounts)
        if (snooze != null) {
            record(
                base(VerdictStage.SNOOZE, VerdictDecision.SUPPRESS, VerdictReason.SNOOZED)
                    .copy(snoozeUntil = snooze.until),
            )
            return
        }
        val context =
            try {
                withTimeout(CONTEXT_BUILD_TIMEOUT) {
                    contextBuilder.build(candidate, objects, zoneResolver.resolve())
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Judge context timed out for recording=${recording.id}; sending without a verdict" }
                record(
                    base(VerdictStage.FAILOVER, VerdictDecision.PUBLISH, VerdictReason.CONTEXT_ERROR)
                        .copy(error = e.describe()),
                )
                send(candidate)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Judge context failed for recording=${recording.id}; sending without a verdict" }
                record(
                    base(VerdictStage.FAILOVER, VerdictDecision.PUBLISH, VerdictReason.CONTEXT_ERROR)
                        .copy(error = e.describe()),
                )
                send(candidate)
                return
            }
        if (context.errors.isNotEmpty()) {
            logger.warn {
                "Judge context for recording=${recording.id} cam=${recording.camId} degraded: ${context.errors.joinToString()}"
            }
        }
        val limiter = rateLimiterProvider.getIfAvailable()
        if (limiter != null && !limiter.tryAcquire()) {
            logger.warn { "Judge rate limit reached; sending recording=${recording.id} (cam=${recording.camId}) unjudged" }
            record(
                base(VerdictStage.FAILOVER, VerdictDecision.PUBLISH, VerdictReason.RATE_LIMITED)
                    .copy(contextJson = context.json, error = "local rate limit"),
            )
            send(candidate)
            return
        }
        val agent = agentProvider.getIfAvailable()
        val outcome =
            try {
                checkNotNull(agent) { "no JudgeAgent bean: the AI preset catalog is not available" }
                agent.judge(judgeRequest(candidate, context))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = failoverReason(e)
                logger.warn(e) {
                    "Judge failed for recording=${recording.id} (cam=${recording.camId}) reason=$reason; sending unjudged"
                }
                record(
                    base(VerdictStage.FAILOVER, VerdictDecision.PUBLISH, reason)
                        .copy(contextJson = context.json, error = e.describe()),
                )
                send(candidate)
                return
            }
        val verdict = outcome.verdict
        val decision =
            if (verdict.decision == JudgeVerdict.Decision.PUBLISH) {
                VerdictDecision.PUBLISH
            } else {
                VerdictDecision.SUPPRESS
            }
        snoozes.set(recording.camId, recording.recordTimestamp, verdict.snoozeMinutes, classCounts)
        val until = snoozes.covers(recording.camId, recording.recordTimestamp, classCounts)?.until
        logger.info {
            "Judge: cam=${recording.camId} verdict=${verdict.decision} reason=${verdict.reason} snooze=${verdict.snoozeMinutes}m " +
                "latency=${outcome.latency.toMillis()}ms preset=${outcome.presetId} recording=${recording.id}"
        }
        record(
            base(VerdictStage.JUDGE, decision, VerdictReason.valueOf(verdict.reason.name)).copy(
                confidence = verdict.confidence,
                summary = verdict.summary.ifBlank { null },
                wanted = verdict.wanted.ifBlank { null },
                snoozeUntil = until,
                presetId = outcome.presetId,
                model = outcome.model,
                latencyMs =
                    outcome.latency
                        .toMillis()
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt(),
                contextJson = context.json,
            ),
        )
        if (decision == VerdictDecision.PUBLISH) send(candidate)
    }

    private fun judgeRequest(
        candidate: JudgeCandidate,
        context: JudgeContextResult,
    ): JudgeRequest =
        JudgeRequest(
            recordingId = candidate.recording.id,
            camId = candidate.recording.camId,
            frames =
                candidate.visualizedFrames
                    .take(properties.maxFrames)
                    .sortedBy { it.frameIndex }
                    .map { DescriptionRequest.FrameImage(it.frameIndex, it.visualizedBytes) },
            contextJson = context.json,
            language = descriptionProperties.common.language,
            maxSnoozeMinutes = properties.maxSnoozeMinutes,
        )

    private suspend fun judgeEnabled(recordingId: UUID): Boolean =
        try {
            withTimeout(SETTINGS_READ_TIMEOUT) { runtimeSettings.judgeEnabled() }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                logger.warn { "Reading the judge switch for $recordingId timed out; failing open" }
                true
            } else {
                throw e
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read the judge switch for $recordingId; failing open" }
            true
        }

    private suspend fun record(verdict: NewNotificationVerdict) {
        try {
            verdicts.record(verdict)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to store the judge verdict for recording=${verdict.recordingId}; the decision is applied anyway"
            }
        }
    }

    private suspend fun send(candidate: JudgeCandidate) {
        try {
            telegram.sendRecordingNotification(
                candidate.recording,
                candidate.visualizedFrames,
                candidate.descriptionSupplier,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to send telegram notification for recording ${candidate.recording.id}" }
        }
    }

    private fun failoverReason(e: Exception): VerdictReason =
        when (e) {
            is DescriptionException.Timeout -> VerdictReason.TIMEOUT
            is DescriptionException.RateLimited -> VerdictReason.RATE_LIMITED
            is DescriptionException.Unauthorized -> VerdictReason.UNAUTHORIZED
            is DescriptionException.InvalidResponse -> VerdictReason.INVALID_RESPONSE
            else -> VerdictReason.TRANSPORT
        }

    /**
     * FAILOVER/TRANSPORT вне внутренних catch [judgeLocked]: кластеризация, лимитер, `valueOf`.
     * Классы считаем отдельно — до `base` в [judgeLocked] их ещё нет, а сама кластеризация может
     * быть тем, что упало.
     */
    private fun unexpectedFailover(
        candidate: JudgeCandidate,
        error: Exception,
    ): NewNotificationVerdict {
        val recording = candidate.recording
        val classes =
            try {
                BboxClusteringHelper
                    .cluster(
                        candidate.detections,
                        trackerProperties.innerIou,
                        trackerProperties.confidenceFloor,
                    ).groupingBy { it.className }
                    .eachCount()
                    .toSortedMap()
                    .entries
                    .joinToString(",") { "${it.key}:${it.value}" }
            } catch (_: Exception) {
                ""
            }
        return NewNotificationVerdict(
            recording.id,
            recording.camId,
            recording.recordTimestamp,
            VerdictStage.FAILOVER,
            VerdictDecision.PUBLISH,
            VerdictReason.TRANSPORT,
            candidate.decision.reason.name,
            classes,
            error = error.describe(),
        )
    }

    /** Класс и сообщение без стека и без чужих строк — в колонке 1024 символа и никаких секретов. */
    private fun Throwable.describe(): String = "${this::class.simpleName}: ${message.orEmpty()}".take(ERROR_MAX)

    private companion object {
        val SETTINGS_READ_TIMEOUT = 5.seconds
        val CONTEXT_BUILD_TIMEOUT = 10.seconds
        const val QUEUE_WARN_THRESHOLD = 20
        const val ERROR_MAX = 1024
    }
}
