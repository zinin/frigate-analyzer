package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AddUserCommandHandler(
    private val userService: TelegramUserService,
    private val properties: TelegramProperties,
) : CommandHandler {
    override val command: String = "adduser"
    override val description: String = "Добавить пользователя"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 10

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val text = message.content.text
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2) {
            reply(message, "Использование: /adduser @username")
            return
        }

        val targetUsername = parts[1].trim().removePrefix("@")
        if (targetUsername.isBlank()) {
            reply(message, "Использование: /adduser @username")
            return
        }

        if (targetUsername == properties.owner) {
            reply(message, "Владелец не может быть добавлен как пользователь.")
            return
        }

        val existing = userService.findByUsername(targetUsername)
        if (existing != null) {
            val statusText = if (existing.status == UserStatus.ACTIVE) "активен" else "приглашён"
            reply(message, "Пользователь @$targetUsername уже $statusText.")
            return
        }

        userService.inviteUser(targetUsername)
        reply(message, "Пользователь @$targetUsername приглашён. Он должен написать /start боту для активации.")
    }
}
