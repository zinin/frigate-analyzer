package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

private val logger = KotlinLogging.logger {}

/**
 * Единственная реализация [DescriptionAgent]. Провайдер-нейтральная оркестрация одной попытки
 * [DescriptionBackend.describe]: семафор на `maxConcurrent`, ожидание слота не дольше
 * `queueTimeout`, общий `withTimeout(timeout)`, по одному повтору на `InvalidResponse` (сразу) и
 * `Transport` (через [TRANSPORT_RETRY_DELAY]) с проверкой остатка бюджета. `Timeout`, `RateLimited`
 * и `Unauthorized` не повторяются.
 *
 * Состояние авторизации провайдера: первый `Unauthorized` после успеха или старта публикует
 * [DescriptionProviderAuthEvent] LOST, первый успех после него RESTORED. Переход делается через
 * `compareAndSet`, поэтому параллельные вызовы дают ровно одно событие, а сам переход и его
 * публикация идут под одним замком: слушатель доставляет владельцу события в порядке публикации,
 * и разъехавшийся порядок оставил бы его с сообщением об отказе при рабочих учётных данных.
 *
 * Не `@Component`: бин создаёт `AiDescriptionAutoConfiguration`, когда есть каталог пресетов.
 */
class DefaultDescriptionAgent(
    private val catalog: DescriptionPresetCatalog,
    descriptionProperties: DescriptionProperties,
    private val eventPublisher: ApplicationEventPublisher,
    // Wall-clock по умолчанию; тесты подставляют TestTimeSource из runTest, чтобы проверка
    // остатка бюджета жила в том же виртуальном времени, что и внешний withTimeout.
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : DescriptionAgent {
    private val commonSection: DescriptionProperties.CommonSection = descriptionProperties.common
    private val semaphore = Semaphore(commonSection.maxConcurrent)
    private val authState = AtomicReference(AuthState.HEALTHY)

    /**
     * Сериализует «сменить состояние и опубликовать». Без него параллельные вызовы успевают
     * поменяться местами между `compareAndSet` и `publishEvent`: отказ переводит в LOST, успех
     * возвращает в HEALTHY и публикует RESTORED первым, а LOST приходит владельцу последним — и
     * больше не снимается, потому что состояние уже HEALTHY и следующий успех события не даст.
     */
    private val authTransitionLock = Any()

    private enum class AuthState { HEALTHY, LOST }

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
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
            // В Task 4 это станет resolver.resolve(): пока пресет всегда один и тот же.
            val entry = catalog.fallback()
            val backend = requireNotNull(entry.backend) { "fallback preset has no backend" }
            return try {
                withTimeout(commonSection.timeout.toMillis()) {
                    executeWithRetry(backend, prepared)
                }
            } catch (e: TimeoutCancellationException) {
                throw DescriptionException.Timeout(cause = e)
            }
        } finally {
            logger.debug {
                "Description via preset '${catalog.fallbackId}' completed in ${callStart.elapsedNow()} " +
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
                onSuccess(backend)
                return result
            } catch (e: DescriptionException.Unauthorized) {
                onUnauthorized(backend, e)
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

    private fun onUnauthorized(
        backend: DescriptionBackend,
        e: DescriptionException.Unauthorized,
    ) {
        synchronized(authTransitionLock) {
            if (!authState.compareAndSet(AuthState.HEALTHY, AuthState.LOST)) return
            logger.error(e) {
                "Description provider '${backend.providerId}' rejected the credentials; descriptions stay " +
                    "unavailable until re-login. Fix: ${backend.authRecoveryHint}"
            }
            publishAuthEvent(
                backend,
                DescriptionProviderAuthEvent(
                    provider = backend.providerId,
                    state = DescriptionProviderAuthEvent.State.LOST,
                    detail = e.detail,
                    recoveryHint = backend.authRecoveryHint,
                ),
                rollbackFrom = AuthState.LOST,
                rollbackTo = AuthState.HEALTHY,
            )
        }
    }

    private fun onSuccess(backend: DescriptionBackend) {
        synchronized(authTransitionLock) {
            if (!authState.compareAndSet(AuthState.LOST, AuthState.HEALTHY)) return
            logger.info { "Description provider '${backend.providerId}' credentials work again" }
            publishAuthEvent(
                backend,
                DescriptionProviderAuthEvent(
                    provider = backend.providerId,
                    state = DescriptionProviderAuthEvent.State.RESTORED,
                    detail = null,
                    recoveryHint = backend.authRecoveryHint,
                ),
                rollbackFrom = AuthState.HEALTHY,
                rollbackTo = AuthState.LOST,
            )
        }
    }

    /**
     * Spring доставляет событие синхронно, на этом же потоке. Слушатель, который бросил, не должен
     * ни выбрасывать уже оплаченный результат, ни съедать переход: состояние уже переключено, и без
     * отката такой же отказ больше никогда не поднял бы событие, а владелец не узнал бы о нём вовсе.
     */
    private fun publishAuthEvent(
        backend: DescriptionBackend,
        event: DescriptionProviderAuthEvent,
        rollbackFrom: AuthState,
        rollbackTo: AuthState,
    ) {
        try {
            eventPublisher.publishEvent(event)
        } catch (e: Exception) {
            authState.compareAndSet(rollbackFrom, rollbackTo)
            logger.warn(e) {
                "Cannot publish ${event.state} auth event for '${backend.providerId}'; " +
                    "the transition will be reported again on the next occurrence"
            }
        }
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
