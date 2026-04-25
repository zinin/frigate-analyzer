package ru.zinin.frigate.analyzer.core.task

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.zinin.frigate.analyzer.telegram.config.SignalLossTelegramGuard

/**
 * Verifies the application context fails fast when signal-loss is enabled but Telegram is disabled.
 * The wiring is enforced by [SignalLossTelegramGuard]; this test ensures the @PostConstruct check
 * fires at context refresh time rather than only at runtime when the no-op notification service
 * silently swallows alerts.
 *
 * Uses [ApplicationContextRunner] rather than @SpringBootTest because we only need to exercise
 * the guard bean and its @ConditionalOnProperty wiring — no R2DBC, no testcontainers, no
 * auto-config interference. The unit-level guard test (`SignalLossTelegramGuardTest`) covers the
 * pure validation logic; this test pins the Spring wiring contract.
 */
class SignalLossConfigConflictIntegrationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(SignalLossTelegramGuard::class.java)

    @Test
    fun `context fails to start when signal-loss is enabled and telegram is disabled`() {
        contextRunner
            .withPropertyValues(
                "application.signal-loss.enabled=true",
                "application.telegram.enabled=false",
            ).run { context ->
                assertThat(context).hasFailed()
                // Walk to the root cause: Spring wraps the IllegalStateException in a
                // BeanCreationException (and possibly more), so checking the wrapper would be
                // brittle against framework upgrades.
                val rootCause = generateSequence(context.startupFailure) { it.cause }.last()
                assertThat(rootCause).isInstanceOf(IllegalStateException::class.java)
                assertThat(rootCause.message).contains(
                    "application.signal-loss.enabled=true",
                    "application.telegram.enabled=true",
                    "TELEGRAM_ENABLED=true",
                    "SIGNAL_LOSS_ENABLED=false",
                )
            }
    }

    @Test
    fun `context starts cleanly when signal-loss is disabled`() {
        contextRunner
            .withPropertyValues(
                "application.signal-loss.enabled=false",
                "application.telegram.enabled=false",
            ).run { context ->
                assertThat(context).hasNotFailed()
                // @ConditionalOnProperty(havingValue="true") must suppress the guard bean — this
                // is what protects existing test contexts (and production with signal-loss off)
                // from being forced to wire a TelegramBot when it isn't needed.
                assertThat(context).doesNotHaveBean(SignalLossTelegramGuard::class.java)
            }
    }
}
