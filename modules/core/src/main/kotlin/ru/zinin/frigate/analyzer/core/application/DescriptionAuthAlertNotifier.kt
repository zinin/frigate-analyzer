package ru.zinin.frigate.analyzer.core.application

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Доставляет владельцу переходы авторизации провайдера описаний: LOST с командой для починки и
 * техническим сообщением провайдера, RESTORED после первого успеха. Дедупликацию делает ядро
 * ai-description (одно событие на переход), здесь только рендер и отправка. События идут в
 * Channel и обрабатываются одним consumer-ом, чтобы RESTORED не обгонял LOST. Таймаут на enqueue
 * как у [StartupTelegramNotifier], чтобы забитая очередь уведомлений не держала consumer вечно.
 *
 * Истёкший таймаут не выбрасывает событие: `TelegramNotificationQueue.enqueue` это `channel.send`
 * с backpressure, и забитая очередь на пять секунд не значит, что она забита навсегда. Ядро
 * ai-description фиксирует переход до публикации и дедуплицирует следующие отказы, поэтому
 * потерянный здесь LOST означал бы, что владелец не узнает об отказе вообще. Попытка повторяется
 * по [RETRY_BACKOFF], и только исчерпав его, событие снимается с ERROR.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionAuthAlertNotifier(
    private val telegramNotificationService: TelegramNotificationService,
    private val messageResolver: MessageResolver,
    /** Параметрами — ради тестов; в проде дефолты. */
    private val alertTimeout: Duration = ALERT_TIMEOUT,
    private val retryBackoff: List<Duration> = RETRY_BACKOFF,
) {
    internal val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("description-auth-alert"))
    private val events = Channel<DescriptionProviderAuthEvent>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)

    init {
        scope.launch {
            for (event in events) {
                try {
                    deliver(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn(e) { "Failed to send description auth alert (${event.authScopeId}, ${event.state})" }
                } finally {
                    pending.decrementAndGet()
                }
            }
        }
    }

    /** Кладёт событие в очередь уведомлений, повторяя, пока в ней нет места. */
    private suspend fun deliver(event: DescriptionProviderAuthEvent) {
        var attempt = 0
        while (true) {
            try {
                withTimeout(alertTimeout.toMillis()) {
                    telegramNotificationService.sendOwnerMessage { language -> render(event, language) }
                }
                return
            } catch (e: TimeoutCancellationException) {
                if (attempt >= retryBackoff.size) {
                    logger.error {
                        "Description auth alert (${event.authScopeId}, ${event.state}) dropped: the notification " +
                            "queue stayed full through ${attempt + 1} attempts of $alertTimeout"
                    }
                    return
                }
                val backoff = retryBackoff[attempt]
                attempt++
                logger.warn {
                    "Description auth alert (${event.authScopeId}, ${event.state}) could not be queued within " +
                        "$alertTimeout; retrying in $backoff (attempt ${attempt + 1})"
                }
                delay(backoff.toMillis())
            }
        }
    }

    @EventListener
    fun onAuthEvent(event: DescriptionProviderAuthEvent) {
        pending.incrementAndGet()
        val result = events.trySend(event)
        if (result.isFailure) {
            pending.decrementAndGet()
            logger.warn { "Description auth alert (${event.authScopeId}, ${event.state}) dropped: channel closed" }
        }
    }

    internal suspend fun waitUntilIdle() {
        while (pending.get() > 0) {
            delay(5)
        }
    }

    internal fun render(
        event: DescriptionProviderAuthEvent,
        language: String,
    ): String =
        when (event.state) {
            DescriptionProviderAuthEvent.State.LOST -> {
                buildString {
                    append(messageResolver.get("ai.description.auth.lost", language, event.authScopeId, event.recoveryHint))
                    event.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        append("\n\n")
                        append(detail.take(DETAIL_MAX_LENGTH))
                    }
                }
            }

            DescriptionProviderAuthEvent.State.RESTORED -> {
                messageResolver.get("ai.description.auth.restored", language, event.authScopeId)
            }
        }

    @PreDestroy
    fun shutdown() {
        events.close()
        scope.cancel()
    }

    private companion object {
        val ALERT_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** Паузы между попытками поставить алерт в очередь: последняя попытка через ~2.5 минуты. */
        val RETRY_BACKOFF: List<Duration> = listOf(Duration.ofSeconds(30), Duration.ofSeconds(120))
        const val DETAIL_MAX_LENGTH = 300
    }
}
