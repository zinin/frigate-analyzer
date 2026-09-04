package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.ChatContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole

private val logger = KotlinLogging.logger {}

/**
 * Экран выбора активного пресета AI-описаний. При выключенной фиче команды нет вовсе — вместе с
 * пунктом в меню владельца.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class AiSettingsCommandHandler(
    private val viewStateFactory: AiSettingsViewStateFactory,
    private val renderer: AiSettingsMessageRenderer,
) : CommandHandler {
    override val command: String = "ai"
    override val requiredRole: UserRole = UserRole.OWNER

    /**
     * Видимость команды определяет `ownerOnly`, а НЕ `requiredRole`:
     * `FrigateAnalyzerBot.registerDefaultCommands()` регистрирует в `BotCommandScopeDefault` всё,
     * что `filterNot { it.ownerOnly }`, и `HelpCommandHandler` печатает такие команды в общем
     * списке. Без этой строки `/ai` попала бы в меню каждого пользователя и в общий раздел
     * `/help`, отбиваясь только на клике.
     */
    override val ownerOnly: Boolean = true

    // 8 занят `/status` из modules/core. Раскладка целиком — в AiSettingsCommandHandlerTest.
    override val order: Int = 9

    override suspend fun BehaviourContext.handle(
        message: ChatContentMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        if (user == null) return
        logger.debug { "/ai opened by chatId=${message.chat.id} username=${user.username}" }
        val rendered = renderer.render(viewStateFactory.build(user.languageCode ?: DEFAULT_LANGUAGE))
        sendTextMessage(message.chat.id, rendered.text, replyMarkup = rendered.keyboard)
    }

    private companion object {
        const val DEFAULT_LANGUAGE = "en"
    }
}
