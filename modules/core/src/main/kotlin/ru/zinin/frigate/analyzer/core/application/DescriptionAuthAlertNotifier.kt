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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Доставляет владельцу переходы авторизации провайдера описаний: LOST с командой для починки и
 * техническим сообщением провайдера, RESTORED после первого успеха. Дедупликацию делает ядро
 * ai-description (одно событие на переход), здесь только рендер и отправка. Устройство повторяет
 * [StartupTelegramNotifier]: свой scope, чтобы доставка не держала поток публикации события, и
 * таймаут, чтобы зависший Telegram не копил корутины.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionAuthAlertNotifier(
    private val telegramNotificationService: TelegramNotificationService,
    private val messageResolver: MessageResolver,
) {
    internal val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("description-auth-alert"))

    @EventListener
    fun onAuthEvent(event: DescriptionProviderAuthEvent) {
        scope.launch {
            try {
                withTimeout(ALERT_TIMEOUT.toMillis()) {
                    telegramNotificationService.sendOwnerMessage { language -> render(event, language) }
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Description auth alert (${event.provider}, ${event.state}) timed out after $ALERT_TIMEOUT" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to send description auth alert (${event.provider}, ${event.state})" }
            }
        }
    }

    internal fun render(
        event: DescriptionProviderAuthEvent,
        language: String,
    ): String =
        when (event.state) {
            DescriptionProviderAuthEvent.State.LOST -> {
                buildString {
                    append(messageResolver.get("ai.description.auth.lost", language, event.provider, event.recoveryHint))
                    event.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        append("\n\n")
                        append(detail.take(DETAIL_MAX_LENGTH))
                    }
                }
            }

            DescriptionProviderAuthEvent.State.RESTORED -> {
                messageResolver.get("ai.description.auth.restored", language, event.provider)
            }
        }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        val ALERT_TIMEOUT: Duration = Duration.ofSeconds(5)
        const val DETAIL_MAX_LENGTH = 300
    }
}
