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
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StartupTelegramNotifier(
    private val telegramNotificationService: TelegramNotificationService,
    private val telegramUserService: TelegramUserService,
    private val messageResolver: MessageResolver,
    private val gitProperties: GitProperties,
    private val buildProperties: BuildProperties,
    private val clock: Clock,
) {
    // Fire-and-forget scope: keeps the startup notification off the ApplicationReadyEvent
    // publisher thread so slow Telegram delivery cannot delay boot/readiness. Lifecycle-bound
    // via @PreDestroy below; SupervisorJob isolates failures of the single child launch.
    internal val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("startup-telegram-notifier"))

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        scope.launch {
            try {
                withTimeout(STARTUP_NOTIFICATION_TIMEOUT.toMillis()) {
                    val ownerLang = telegramUserService.getOwnerLanguage() ?: DEFAULT_LANGUAGE
                    // BuildProperties / GitProperties getters may return null (per Spring Javadoc);
                    // null-safe formatting prevents "Version: null" if the build/git plugin didn't run.
                    val text =
                        buildString {
                            appendLine(messageResolver.get("startup.notification.message", ownerLang))
                            appendLine("Version: ${buildProperties.version ?: UNKNOWN}")
                            appendLine("Commit: ${gitProperties.commitId?.take(8) ?: UNKNOWN}")
                            appendLine("Build time: ${buildProperties.time ?: UNKNOWN}")
                            append("Started: ${Instant.now(clock)}")
                        }
                    telegramNotificationService.sendOwnerMessage(text)
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Startup notification timed out after $STARTUP_NOTIFICATION_TIMEOUT: ${e.message}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to send startup notification" }
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        val STARTUP_NOTIFICATION_TIMEOUT: Duration = Duration.ofSeconds(5)
        const val UNKNOWN: String = "<unknown>"
        const val DEFAULT_LANGUAGE: String = "en"
    }
}
