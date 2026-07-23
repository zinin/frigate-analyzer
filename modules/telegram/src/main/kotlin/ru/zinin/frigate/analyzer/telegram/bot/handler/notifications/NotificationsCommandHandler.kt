package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

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
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class NotificationsCommandHandler(
    private val userService: TelegramUserService,
    private val viewStateFactory: NotificationsViewStateFactory,
    private val renderer: NotificationsMessageRenderer,
) : CommandHandler {
    override val command: String = "notifications"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 7

    override suspend fun BehaviourContext.handle(
        message: ChatContentMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        if (user == null) return
        val chatId = message.chat.id
        val isOwner = userService.isOwner(user.username)
        logger.debug { "/notifications opened by chatId=$chatId username=${user.username} isOwner=$isOwner" }

        val state = viewStateFactory.build(user, isOwner)
        val rendered = renderer.render(state)
        sendTextMessage(chatId, rendered.text, replyMarkup = rendered.keyboard)
    }
}
