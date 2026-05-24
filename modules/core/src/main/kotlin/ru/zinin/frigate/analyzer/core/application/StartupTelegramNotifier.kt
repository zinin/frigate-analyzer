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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

// iter-1 review §D7: @ConditionalOnBean prevents NoSuchBeanDefinitionException in minimal contexts
// where GitProperties/BuildProperties may not exist (e.g., tests without actuator git-info / spring-boot build-info).
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(GitProperties::class, BuildProperties::class)
class StartupTelegramNotifier(
    private val telegramNotificationService: TelegramNotificationService,
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
        // iter-2 CONCERN-5: BuildProperties / GitProperties getters may return null
        // (per Spring Javadoc). Null-safe formatting guarantees the startup message
        // doesn't break with "Version: null" if build/git plugin didn't run.
        val text =
            buildString {
                appendLine("🟢 Frigate Analyzer запущен")
                appendLine("Version: ${buildProperties.version ?: UNKNOWN}")
                appendLine("Commit: ${gitProperties.commitId?.take(8) ?: UNKNOWN}")
                appendLine("Build time: ${buildProperties.time ?: UNKNOWN}")
                append("Started: ${Instant.now(clock)}")
            }
        scope.launch {
            try {
                // iter-1 review §D5 — safety net for future regressions (notificationQueue.enqueue is
                // microsec in normal path but may become blocking if the buffer ever fills).
                withTimeout(STARTUP_NOTIFICATION_TIMEOUT.toMillis()) {
                    telegramNotificationService.sendOwnerMessage(text)
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Startup notification timed out after $STARTUP_NOTIFICATION_TIMEOUT: ${e.message}" }
            } catch (e: CancellationException) {
                // Scope was cancelled (shutdown). Let cancellation propagate so the coroutine
                // terminates in the expected state instead of being recorded as a failure.
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
    }
}
