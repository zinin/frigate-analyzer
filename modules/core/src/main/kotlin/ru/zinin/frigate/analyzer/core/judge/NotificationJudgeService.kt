package ru.zinin.frigate.analyzer.core.judge

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val inFlight = Semaphore(properties.maxInFlight)

    /**
     * Точка входа фасада: работа идёт в [JudgeCoroutineScope], и в норме вызов возвращается сразу.
     *
     * Сверх `maxInFlight` кандидатов в памяти вызов не ждёт освобождения, а сам отправляет запись
     * неосуждённой — ровно как переполнение очереди камеры. Ожидание было бы хуже вдвойне: оно
     * глушило бы поканальный клапан [QUEUE_WARN_THRESHOLD] (при нескольких камерах общий потолок
     * упирается раньше, чем любая из них дойдёт до своей глубины) и добавляло бы в корутине
     * consumer-а точку приостановки, отмена в которой теряет запись — фасад уже вызвал
     * `saveProcessingResult`. Рассылка при этом идёт в корутине вызывающего: новых корутин с ещё
     * одним комплектом кадров не появляется, а пайплайн получает то самое давление, которое было
     * до судьи, когда фасад звал рассылку сам.
     *
     * Старт [CoroutineStart.ATOMIC] — не оптимизация, а единственный способ довести кандидата до
     * обработчика отмены в [process]. При обычном старте scope, погашенный мгновением раньше, вернул
     * бы уже отменённую задачу, чьё тело не выполняется вовсе: ни строки вердикта, ни отправки.
     * Окно открыто на всей остановке — `FrameAnalysisPipeline.stop()` отменяет consumer-ов без
     * join. С ATOMIC тело стартует, отмену поднимает первая же точка приостановки, и досылку делает
     * та же ветка, что и для кандидата, отменённого уже в работе. Он же делает безопасным
     * освобождение разрешения в `finally`: тело, которое не стартовало, его бы не вернуло.
     */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun submit(candidate: JudgeCandidate): Job {
        if (!inFlight.tryAcquire()) {
            logger.warn {
                "Judge already holds ${properties.maxInFlight} candidates; sending " +
                    "recording=${candidate.recording.id} (cam=${candidate.recording.camId}) unjudged"
            }
            // Неделимо: запись помечена обработанной, и отмена между строкой вердикта и рассылкой
            // потеряла бы её совсем — тот же довод, что и в ветке отмены [process].
            withContext(NonCancellable) {
                record(
                    unexpectedFailover(
                        candidate,
                        IllegalStateException("judge in-flight limit ${properties.maxInFlight}"),
                    ),
                )
                send(candidate, AtomicBoolean(false))
            }
            return Job().apply { complete() }
        }
        return try {
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    process(candidate)
                } finally {
                    inFlight.release()
                }
            }
        } catch (e: Throwable) {
            // Защитная: `launch` тело синхронно не исполняет, так что сюда попадём, только если он
            // не смог создать задачу вовсе — тогда `finally` выше не отработает и вернуть
            // разрешение больше некому.
            inFlight.release()
            throw e
        }
    }

    /**
     * Снимок для `/status`: только активные по стенным часам. Сам реестр при этом не чистим —
     * [SnoozeRegistry.covers] меряет окно от времени ЗАПИСИ, а не от стены, и при разборе бэклога
     * от новых к старым кандидат с меткой внутри окна приходит уже после того, как `until` по
     * стенным часам прошёл. Чистка по часам выключила бы snooze ровно там, ради чего он сделан.
     */
    fun snapshotSnoozes(): List<CameraSnooze> {
        val now = clock.instant()
        return snoozes.snapshot().filter { it.until.isAfter(now) }
    }

    internal suspend fun process(candidate: JudgeCandidate) {
        val camId = candidate.recording.camId
        val waiting = queued.computeIfAbsent(camId) { AtomicInteger() }
        val depth = waiting.incrementAndGet()
        // Ставится ВНУТРИ send() до вызова Telegram, который идёт под NonCancellable: рассылка
        // неделима, поэтому флаг означает «рассылка отработала целиком», а не «могла оборваться
        // посередине».
        val handedOver = AtomicBoolean(false)
        if (depth == QUEUE_WARN_THRESHOLD + 1) {
            logger.warn { "Judge queue for cam=$camId holds $depth candidates; the model is slower than the camera" }
        }
        try {
            if (depth > QUEUE_WARN_THRESHOLD) {
                logger.warn {
                    "Judge queue for cam=$camId is full (depth=$depth); sending recording=${candidate.recording.id} unjudged"
                }
                record(unexpectedFailover(candidate, IllegalStateException("judge queue depth $depth")))
                send(candidate, handedOver)
                return
            }
            perCameraMutex.computeIfAbsent(camId) { Mutex() }.withLock { judgeLocked(candidate, handedOver) }
        } catch (e: CancellationException) {
            // Фасад уже пометил запись обработанной. Без отправки под NonCancellable docker stop
            // (cancelAndJoin 10 с) молча теряет очередь камеры: пайплайн запись не повторит.
            //
            // Но повторять рассылку, которая уже началась, нельзя: она под NonCancellable дошла до
            // конца, и второй заход дал бы получателям второе сообщение, а notification_verdicts —
            // вторую строку на ту же запись; и счётчики /status, и /verdicts считали бы её дважды.
            withContext(NonCancellable) {
                if (handedOver.get()) {
                    logger.warn {
                        "Judge cancelled around the send for recording=${candidate.recording.id} cam=$camId; " +
                            "the verdict is already recorded and the fan-out has finished"
                    }
                } else {
                    logger.warn {
                        "Judge cancelled for recording=${candidate.recording.id} cam=$camId; sending unjudged"
                    }
                    record(unexpectedFailover(candidate, e))
                    send(candidate, handedOver)
                }
            }
            throw e
        } catch (e: Exception) {
            logger.warn(e) {
                "Judge failed unexpectedly for recording=${candidate.recording.id} cam=$camId; sending unjudged"
            }
            record(unexpectedFailover(candidate, e))
            send(candidate, handedOver)
        } finally {
            waiting.decrementAndGet()
        }
    }

    private suspend fun judgeLocked(
        candidate: JudgeCandidate,
        handedOver: AtomicBoolean,
    ) {
        val recording = candidate.recording
        val objects =
            BboxClusteringHelper.clusterWithMembers(
                candidate.detections,
                trackerProperties.innerIou,
                trackerProperties.confidenceFloor,
            )
        val classCounts = objects.groupingBy { it.representative.className }.eachCount().toSortedMap()
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
            send(candidate, handedOver)
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
        // Строго после snooze и строго до контекста. Подавленная серия так и остаётся бесплатной —
        // это ради неё snooze и сделан, — а всё платное ниже (SQL контекста, слот лимита, модель)
        // защищено. Тот же фильтр применяет и сама рассылка, только уже после вердикта.
        if (!hasRecipients(recording.id)) {
            record(base(VerdictStage.BYPASS, VerdictDecision.SUPPRESS, VerdictReason.NO_RECIPIENTS))
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
                send(candidate, handedOver)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Judge context failed for recording=${recording.id}; sending without a verdict" }
                record(
                    base(VerdictStage.FAILOVER, VerdictDecision.PUBLISH, VerdictReason.CONTEXT_ERROR)
                        .copy(error = e.describe()),
                )
                send(candidate, handedOver)
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
            send(candidate, handedOver)
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
                send(candidate, handedOver)
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
            // snoozeUntil, а не только запрошенные минуты: реестр отбрасывает вердикт по записи
            // старше своего якоря, и тогда в строке вердикта и в /status укрытия не будет.
            "Judge: cam=${recording.camId} verdict=${verdict.decision} reason=${verdict.reason} " +
                "snooze=${verdict.snoozeMinutes}m snoozeUntil=$until latency=${outcome.latency.toMillis()}ms " +
                "preset=${outcome.presetId} recording=${recording.id}"
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
        if (decision == VerdictDecision.PUBLISH) send(candidate, handedOver)
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

    /**
     * Есть ли кому доставить. Fail-open к «есть»: пропустить настоящее уведомление из-за
     * недоступной базы хуже, чем заплатить за вердикт, который никто не увидит.
     *
     * Граница по времени обязательна, как у [judgeEnabled]: это запрос к базе, а пул R2DBC не
     * задаёт `max-acquire-time`, так что исчерпанный пул подвесил бы его навсегда — держа при этом
     * и мьютекс камеры, и разрешение in-flight, то есть в итоге и приём записей.
     */
    private suspend fun hasRecipients(recordingId: UUID): Boolean =
        try {
            withTimeout(SETTINGS_READ_TIMEOUT) { telegram.hasRecordingRecipients() }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                logger.warn { "Reading the subscriber list for $recordingId timed out; judging anyway" }
                true
            } else {
                throw e
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read the subscriber list for $recordingId; judging anyway" }
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

    /**
     * Рассылка неделима: [handedOver] взводится до вызова, а сам вызов идёт под [NonCancellable].
     * Без этого отмена при остановке приложения могла застать `sendRecordingNotification` на
     * приостановке ДО первой постановки в очередь (чтение подписчиков из базы) — флаг уже стоял бы,
     * ветка отмены в [process] пропустила бы досылку, и запись пропала бы совсем: фасад пометил её
     * обработанной, пайплайн её не повторит. Ставить флаг «по факту первого enqueue» нечем: очередь
     * скрыта за `TelegramNotificationService`. Плата — та же, что уже принята для досылки в ветке
     * отмены: остановка ждёт рассылку до 10 с, отпущенных [JudgeCoroutineScope], а дальше пишет
     * предупреждение и идёт дальше — рассылка доработает уже на фоне закрывающихся бинов. Повиснуть
     * она не может: `enqueue` пишет в ограниченный канал, который закрывается вместе с очередью.
     */
    private suspend fun send(
        candidate: JudgeCandidate,
        handedOver: AtomicBoolean,
    ) {
        handedOver.set(true)
        withContext(NonCancellable) {
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
