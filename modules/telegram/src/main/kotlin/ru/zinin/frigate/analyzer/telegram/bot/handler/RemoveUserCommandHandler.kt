package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class RemoveUserCommandHandler(
    private val userService: TelegramUserService,
    private val properties: TelegramProperties,
) : CommandHandler {
    override val command: String = "removeuser"
    override val description: String = "Удалить пользователя"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 11

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        role: UserRole?,
    ) {
        val text = message.content.text
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2) {
            reply(message, "Использование: /removeuser @username")
            return
        }

        val targetUsername = parts[1].trim().removePrefix("@")
        if (targetUsername.isBlank()) {
            reply(message, "Использование: /removeuser @username")
            return
        }

        if (targetUsername == properties.owner) {
            reply(message, "Владелец не может быть удалён.")
            return
        }

        val removed = userService.removeUser(targetUsername)
        if (removed) {
            reply(message, "Пользователь @$targetUsername удалён.")
        } else {
            reply(message, "Пользователь @$targetUsername не найден.")
        }
    }
}
