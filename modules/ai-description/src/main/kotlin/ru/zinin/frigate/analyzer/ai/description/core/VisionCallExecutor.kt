package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

private val logger = KotlinLogging.logger {}

data class VisionOutcome<T>(
    val value: T,
    val preset: DescriptionPreset,
    val elapsed: kotlin.time.Duration,
)

/**
 * Провайдер-нейтральное исполнение одной vision-задачи: резолюция пресета до семафора, семафор,
 * queueTimeout, timeout, retry по InvalidResponse и Transport с проверкой остатка бюджета, downscale
 * кадров, отчёт в ProviderAuthTracker. Разбор ответа ([parse]) выполняется внутри цикла повторов:
 * InvalidResponse из парсера повторяет вызов так же, как раньше повторял его backend.
 *
 * У каждой фазы вызова свой потолок, и `withTimeout` покрывает не весь вызов: резолюция пресета
 * ограничена собственным потолком [ActivePresetResolver] (его истечение даёт пресет по умолчанию, а
 * не исключение), ожидание слота семафора — `queueTimeout`, работа модели вместе с повторами —
 * `withTimeout(timeout)`. Повторы: по одному на `InvalidResponse` (сразу) и `Transport` (через
 * [TRANSPORT_RETRY_DELAY]) с проверкой остатка бюджета. `Timeout`, `RateLimited` и `Unauthorized`
 * не повторяются.
 *
 * Пресет резолвится один раз на вызов и ДО семафора: повторы обязаны идти в тот же пресет, что и
 * первая попытка (иначе лог одной записи назвал бы двух разных провайдеров), а чтение настроек —
 * ввод-вывод, которому нельзя ни удерживать пермит, ни съедать бюджет, отпущенный модели. Принятая
 * плата: вызов, простоявший в очереди, применит пресет, актуальный на момент постановки в очередь;
 * окно ограничено сверху `queueTimeout`.
 *
 * Состояние авторизации executor не держит: исход каждой попытки уходит в [ProviderAuthTracker] под
 * областью учётных данных backend-а ([VisionBackend.authScopeId]), а переходы, дедупликацию и
 * публикацию событий делает трекер. Одного состояния на executor и не хватило бы: пресетов за вызов
 * теперь несколько, и успех BYOK-пресета снимал бы LOST, поднятый пресетом на протухшем OAuth.
 *
 * Не `@Component`: бин создаёт `AiDescriptionAutoConfiguration` по имени
 * (`descriptionVisionCallExecutor` / `judgeVisionCallExecutor`); экземпляры отличаются именем параметра, не типом.
 */
class VisionCallExecutor(
    private val resolver: ActivePresetResolver,
    private val authTracker: ProviderAuthTracker,
    private val limits: VisionLimits,
    private val label: String,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val semaphore = Semaphore(limits.maxConcurrent)

    suspend fun <T> execute(
        request: VisionRequest,
        parse: (String) -> T,
    ): VisionOutcome<T> {
        // Один раз на вызов и до захвата пермита: чтение настроек — ввод-вывод. Под пермитом при
        // maxConcurrent=2 и зависшем пуле R2DBC оба слота заняли бы корутины, ждущие одно и то же
        // чтение, а внешний withTimeout ещё не начался и не помог бы.
        val entry = resolver.resolve()
        val backend = requireNotNull(entry.backend) { "resolved preset '${entry.view.id}' has no backend" }

        // Флаг, а не «acquire последним выражением withTimeout»: если дедлайн истекает в момент
        // между возвратом acquire() и завершением блока, kotlinx отбрасывает результат и бросает
        // TimeoutCancellationException — пермит уже взят. Один внешний finally отпускает его и
        // на TCE очереди, и на отмену родителя после acquire: раньше второй try начинался позже,
        // и обычный CancellationException утекал с занятым слотом.
        var acquired = false
        try {
            try {
                withTimeout(limits.queueTimeout.toMillis()) {
                    semaphore.acquire()
                    acquired = true
                }
            } catch (e: CancellationException) {
                if (acquired) {
                    semaphore.release()
                    acquired = false
                }
                if (e is TimeoutCancellationException) {
                    throw DescriptionException.Timeout(cause = e)
                }
                throw e
            }

            val callStart = timeSource.markNow()
            try {
                // Уменьшение кадров держим под семафором, но вне withTimeout: это CPU-работа, чей
                // размер известен заранее, и она не должна съедать бюджет, отпущенный модели. Отсюда
                // же и перехват: вне attempt() исключение ушло бы из execute() сырым, мимо контракта
                // DescriptionException, а описание важнее уменьшения — кадры пойдут как есть.
                val prepared =
                    try {
                        downscaleFrames(request)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Cannot downscale frames of ${request.requestId}; sending them unchanged" }
                        request
                    }
                val value =
                    try {
                        withTimeout(limits.timeout.toMillis()) {
                            executeWithRetry(backend, prepared, parse)
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw DescriptionException.Timeout(cause = e)
                    }
                return VisionOutcome(value, entry.view, callStart.elapsedNow())
            } finally {
                // Строка остаётся в finally: в теле try она пропадала бы ровно на путях с исключением,
                // а именно они и интересны при разборе.
                logger.debug {
                    "$label via preset '${entry.view.id}' completed in ${callStart.elapsedNow()} for ${request.requestId}"
                }
            }
        } finally {
            if (acquired) semaphore.release()
        }
    }

    /** Один проход на запрос, до повторов: провайдер получает уже готовые кадры. */
    private suspend fun downscaleFrames(request: VisionRequest): VisionRequest {
        val maxSide = limits.maxImageSide
        if (maxSide <= 0 || request.frames.isEmpty()) return request
        val before = request.frames.sumOf { it.bytes.size }
        val frames =
            withContext(Dispatchers.Default) {
                request.frames.map { frame -> frame.copy(bytes = FrameDownscaler.downscale(frame.bytes, maxSide)) }
            }
        val after = frames.sumOf { it.bytes.size }
        if (after != before) {
            logger.debug {
                "Downscaled ${frames.size} frames of ${request.requestId} to <=$maxSide px: " +
                    "$before -> $after bytes"
            }
        }
        return request.copy(frames = frames)
    }

    private suspend fun <T> executeWithRetry(
        backend: VisionBackend,
        request: VisionRequest,
        parse: (String) -> T,
    ): T {
        val overallStart = timeSource.markNow()
        val totalBudget = limits.timeout.toKotlinDuration()
        var invalidRetries = 0
        var transportRetries = 0
        while (true) {
            try {
                val result = attempt(backend, request, parse)
                authTracker.onSuccess(backend.authScopeId, backend.authRecoveryHint)
                return result
            } catch (e: DescriptionException.Unauthorized) {
                authTracker.onUnauthorized(backend.authScopeId, e, backend.authRecoveryHint)
                throw e
            } catch (e: DescriptionException.InvalidResponse) {
                if (invalidRetries >= 1) throw e
                invalidRetries++
                val remaining = totalBudget - overallStart.elapsedNow()
                if (remaining <= INVALID_RESPONSE_RETRY_MIN_BUDGET) {
                    logger.warn(e) {
                        "Invalid response from '${backend.providerId}' but retry budget exhausted " +
                            "(remaining=$remaining); giving up"
                    }
                    throw e
                }
                logger.warn(e) {
                    "Invalid response from '${backend.providerId}', retrying " +
                        "(attempt ${invalidRetries + 1}, remaining budget=$remaining)"
                }
            } catch (e: DescriptionException.Transport) {
                if (transportRetries >= 1) throw e
                transportRetries++
                val remaining = totalBudget - overallStart.elapsedNow()
                if (remaining <= TRANSPORT_RETRY_MIN_BUDGET) {
                    logger.warn(e) {
                        "Transport error from '${backend.providerId}' but retry budget exhausted " +
                            "(remaining=$remaining); giving up"
                    }
                    throw e
                }
                logger.warn(e) {
                    "Transport error from '${backend.providerId}', retrying in $TRANSPORT_RETRY_DELAY " +
                        "(remaining budget=$remaining)"
                }
                delay(TRANSPORT_RETRY_DELAY)
            }
        }
    }

    /** Одна попытка; всё, что не DescriptionException и не отмена, становится Transport. */
    private suspend fun <T> attempt(
        backend: VisionBackend,
        request: VisionRequest,
        parse: (String) -> T,
    ): T =
        try {
            parseAnswer(backend.complete(request, limits.timeout), parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DescriptionException) {
            throw e
        } catch (e: Throwable) {
            throw DescriptionException.Transport(e)
        }

    /**
     * Провайдер может вернуть два представления одного ответа: Grok кладёт объект по `--json-schema`
     * в `structuredOutput`, а тот же объект текстом — в `text`, и эндпоинт, применивший схему лишь
     * частично, оставляет годным именно текст. Задача разбирает основное представление; если оно
     * негодно, запасное разбирается в той же попытке — ответ уже оплачен, и повтор вызова модели за
     * него платил бы второй раз, а при таком же частичном ответе оставил бы запись без описания или
     * без вердикта. Негодны оба — наружу уходит ошибка основного: провайдер считает основным его.
     */
    private fun <T> parseAnswer(
        response: VisionResponse,
        parse: (String) -> T,
    ): T =
        try {
            parse(response.text)
        } catch (primary: DescriptionException.InvalidResponse) {
            val fallback = response.fallback ?: throw primary
            val value =
                try {
                    parse(fallback)
                } catch (_: DescriptionException.InvalidResponse) {
                    throw primary
                }
            logger.warn(primary) {
                "$label could not parse the primary payload; used the fallback representation of the same answer"
            }
            value
        }

    companion object {
        private val TRANSPORT_RETRY_DELAY = 5.seconds

        // Минимальный остаток бюджета перед повтором: пауза перед вызовом плюс запас на один
        // реальный вызов провайдера. Иначе внешний withTimeout отменил бы повтор посередине и
        // вместо честного Transport получился бы вводящий в заблуждение Timeout.
        private val TRANSPORT_RETRY_MIN_BUDGET = 10.seconds

        // То же для InvalidResponse, без паузы перед вызовом.
        private val INVALID_RESPONSE_RETRY_MIN_BUDGET = 5.seconds
    }
}
