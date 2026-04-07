package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class UsersCommandHandler(
    private val userService: TelegramUserService,
    private val msg: MessageResolver,
) : CommandHandler {
    override val command: String = "users"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 12

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val lang = user?.languageCode ?: "ru"
        val users = userService.getAllUsers()
        if (users.isEmpty()) {
            reply(message, msg.get("command.users.empty", lang))
            return
        }

        val text =
            buildString {
                appendLine(msg.get("command.users.header", lang))
                appendLine()
                users.forEach { u ->
                    val statusEmoji = if (u.status == UserStatus.ACTIVE) "✅" else "⏳"
                    val statusText = msg.get("common.status.${u.status.name.lowercase()}", lang)
                    appendLine("$statusEmoji @${u.username} - $statusText")
                }
            }

        reply(message, text)
    }
}
