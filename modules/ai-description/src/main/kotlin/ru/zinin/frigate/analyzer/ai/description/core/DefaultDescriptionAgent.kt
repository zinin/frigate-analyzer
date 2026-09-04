package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

private val logger = KotlinLogging.logger {}

/**
 * Единственная реализация [DescriptionAgent]. Провайдер-нейтральная оркестрация одной попытки
 * [DescriptionBackend.describe]. У каждой фазы вызова свой потолок, и `withTimeout` покрывает не
 * весь вызов: резолюция пресета ограничена собственным потолком [ActivePresetResolver] (его
 * истечение даёт пресет по умолчанию, а не исключение), ожидание слота семафора — `queueTimeout`,
 * работа модели вместе с повторами — `withTimeout(timeout)`. Повторы: по одному на `InvalidResponse`
 * (сразу) и `Transport` (через [TRANSPORT_RETRY_DELAY]) с проверкой остатка бюджета. `Timeout`,
 * `RateLimited` и `Unauthorized` не повторяются.
 *
 * Пресет резолвится один раз на вызов и ДО семафора: повторы обязаны идти в тот же пресет, что и
 * первая попытка (иначе лог одной записи назвал бы двух разных провайдеров), а чтение настроек —
 * ввод-вывод, которому нельзя ни удерживать пермит, ни съедать бюджет, отпущенный модели. Принятая
 * плата: вызов, простоявший в очереди, применит пресет, актуальный на момент постановки в очередь;
 * окно ограничено сверху `queueTimeout`.
 *
 * Состояние авторизации агент не держит: исход каждой попытки уходит в [ProviderAuthTracker] под
 * областью учётных данных backend-а ([DescriptionBackend.authScopeId]), а переходы, дедупликацию и
 * публикацию событий делает трекер. Одного состояния на агента и не хватило бы: пресетов за вызов
 * теперь несколько, и успех BYOK-пресета снимал бы LOST, поднятый пресетом на протухшем OAuth.
 *
 * Не `@Component`: бин создаёт `AiDescriptionAutoConfiguration`, когда есть каталог пресетов.
 */
class DefaultDescriptionAgent(
    private val resolver: ActivePresetResolver,
    private val authTracker: ProviderAuthTracker,
    descriptionProperties: DescriptionProperties,
    // Wall-clock по умолчанию; тесты подставляют TestTimeSource из runTest, чтобы проверка
    // остатка бюджета жила в том же виртуальном времени, что и внешний withTimeout.
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : DescriptionAgent {
    private val commonSection: DescriptionProperties.CommonSection = descriptionProperties.common
    private val semaphore = Semaphore(commonSection.maxConcurrent)

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        // Один раз на вызов и до захвата пермита: чтение настроек — ввод-вывод. Под пермитом при
        // maxConcurrent=2 и зависшем пуле R2DBC оба слота заняли бы корутины, ждущие одно и то же
        // чтение, а внешний withTimeout ещё не начался и не помог бы.
        val entry = resolver.resolve()
        val backend = requireNotNull(entry.backend) { "resolved preset '${entry.view.id}' has no backend" }

        try {
            withTimeout(commonSection.queueTimeout.toMillis()) {
                semaphore.acquire()
            }
        } catch (e: TimeoutCancellationException) {
            throw DescriptionException.Timeout(cause = e)
        }

        val callStart = timeSource.markNow()
        try {
            // Уменьшение кадров держим под семафором, но вне withTimeout: это CPU-работа, чей
            // размер известен заранее, и она не должна съедать бюджет, отпущенный модели. Отсюда
            // же и перехват: вне attempt() исключение ушло бы из describe() сырым, мимо контракта
            // DescriptionAgent, а описание важнее уменьшения — кадры пойдут как есть.
            val prepared =
                try {
                    downscaleFrames(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Cannot downscale frames of recording ${request.recordingId}; sending them unchanged" }
                    request
                }
            return try {
                withTimeout(commonSection.timeout.toMillis()) {
                    executeWithRetry(backend, prepared)
                }
            } catch (e: TimeoutCancellationException) {
                throw DescriptionException.Timeout(cause = e)
            }
        } finally {
            // Строка остаётся в finally: в теле try она пропадала бы ровно на путях с исключением,
            // а именно они и интересны при разборе.
            logger.debug {
                "Description via preset '${entry.view.id}' completed in ${callStart.elapsedNow()} " +
                    "for recording ${request.recordingId}"
            }
            semaphore.release()
        }
    }

    /** Один проход на запрос, до повторов: провайдер получает уже готовые кадры. */
    private suspend fun downscaleFrames(request: DescriptionRequest): DescriptionRequest {
        val maxSide = commonSection.maxImageSide
        if (maxSide <= 0 || request.frames.isEmpty()) return request
        val before = request.frames.sumOf { it.bytes.size }
        val frames =
            withContext(Dispatchers.Default) {
                request.frames.map { frame -> frame.copy(bytes = FrameDownscaler.downscale(frame.bytes, maxSide)) }
            }
        val after = frames.sumOf { it.bytes.size }
        if (after != before) {
            logger.debug {
                "Downscaled ${frames.size} frames of recording ${request.recordingId} to <=$maxSide px: " +
                    "$before -> $after bytes"
            }
        }
        return request.copy(frames = frames)
    }

    private suspend fun executeWithRetry(
        backend: DescriptionBackend,
        request: DescriptionRequest,
    ): DescriptionResult {
        val overallStart = timeSource.markNow()
        val totalBudget = commonSection.timeout.toKotlinDuration()
        var invalidRetries = 0
        var transportRetries = 0
        while (true) {
            try {
                val result = attempt(backend, request)
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
    private suspend fun attempt(
        backend: DescriptionBackend,
        request: DescriptionRequest,
    ): DescriptionResult =
        try {
            backend.describe(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DescriptionException) {
            throw e
        } catch (e: Throwable) {
            throw DescriptionException.Transport(e)
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
