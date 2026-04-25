package ru.zinin.frigate.analyzer.telegram.config

import dev.inmo.tgbotapi.bot.TelegramBot
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Fails startup with a clear message when `application.signal-loss.enabled=true` is paired with
 * `application.telegram.enabled=false`. Signal-loss notifications have no transport other than
 * Telegram, so the combination is unsupported by design — we surface it as a hard startup error
 * with actionable guidance.
 *
 * Without this pre-flight check the conflict is detected only later, at runtime, via the monitor
 * task's `notificationService.sendCameraSignalLost(...)` round-trip into the no-op notification
 * service — far too late and without a clean fail-fast message pointing the operator at the
 * offending flags.
 *
 * Probing `ObjectProvider<TelegramBot>` is more robust than re-parsing the property: it answers
 * the actual question "is the Telegram subsystem wired?" and also catches a `telegram.enabled=true`
 * config with a missing bot token, which would otherwise fail with the same confusing
 * `NoSuchBeanDefinitionException` shape downstream.
 */
@Component
@ConditionalOnProperty("application.signal-loss.enabled", havingValue = "true")
class SignalLossTelegramGuard(
    private val telegramBotProvider: ObjectProvider<TelegramBot>,
) {
    @PostConstruct
    fun validate() {
        check(telegramBotProvider.getIfAvailable() != null) {
            "application.signal-loss.enabled=true requires application.telegram.enabled=true. " +
                "Signal-loss notifications have no transport without Telegram; with Telegram " +
                "disabled there is no way to deliver camera-signal-lost / -recovered alerts. " +
                "Either set TELEGRAM_ENABLED=true or SIGNAL_LOSS_ENABLED=false."
        }
    }
}
