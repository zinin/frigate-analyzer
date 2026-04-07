package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AddUserCommandHandler(
    private val userService: TelegramUserService,
    private val properties: TelegramProperties,
    private val msg: MessageResolver,
) : CommandHandler {
    override val command: String = "adduser"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 10

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val lang = user?.languageCode ?: "ru"
        val text = message.content.text
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2) {
            reply(message, msg.get("command.adduser.usage", lang))
            return
        }

        val targetUsername = parts[1].trim().removePrefix("@")
        if (targetUsername.isBlank()) {
            reply(message, msg.get("command.adduser.usage", lang))
            return
        }

        if (targetUsername == properties.owner) {
            reply(message, msg.get("command.adduser.error.owner", lang))
            return
        }

        val existing = userService.findByUsername(targetUsername)
        if (existing != null) {
            val statusText = msg.get("common.status.${existing.status.name.lowercase()}", lang)
            reply(message, msg.get("command.adduser.already.exists", lang, targetUsername, statusText))
            return
        }

        userService.inviteUser(targetUsername)
        reply(message, msg.get("command.adduser.invited", lang, targetUsername))
    }
}
