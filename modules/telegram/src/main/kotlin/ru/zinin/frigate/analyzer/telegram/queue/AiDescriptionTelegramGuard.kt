package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Fails startup with a clear message when `application.ai.description.enabled=true` is paired with
 * `application.telegram.enabled=false`. Without this guard Spring reports a bare
 * `NoSuchBeanDefinitionException` for `DescriptionEditJobRunner`'s constructor autowire of
 * `TelegramBot`, which is a correct but confusing signal for that config combination.
 *
 * AI descriptions have no transport other than Telegram, so the combination is unsupported by
 * design — we surface it as a hard startup error with actionable guidance. `DescriptionEditJobRunner`
 * uses `@DependsOn("aiDescriptionTelegramGuard")` so this check fires before the runner is wired.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class AiDescriptionTelegramGuard(
    private val telegramBotProvider: ObjectProvider<TelegramBot>,
) {
    @PostConstruct
    fun validate() {
        check(telegramBotProvider.getIfAvailable() != null) {
            "application.ai.description.enabled=true requires application.telegram.enabled=true. " +
                "AI-generated descriptions are delivered only through Telegram notifications; with " +
                "Telegram disabled, DescriptionEditJobRunner cannot be wired (no TelegramBot bean). " +
                "Either set TELEGRAM_ENABLED=true or APP_AI_DESCRIPTION_ENABLED=false."
        }
    }
}
