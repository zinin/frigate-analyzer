package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class UsersCommandHandler(
    private val userService: TelegramUserService,
) : CommandHandler {
    override val command: String = "users"
    override val description: String = "Список пользователей"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 12

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val users = userService.getAllUsers()
        if (users.isEmpty()) {
            reply(message, "Нет зарегистрированных пользователей.")
            return
        }

        val text =
            buildString {
                appendLine("👥 Пользователи:")
                appendLine()
                users.forEach { user ->
                    val statusEmoji = if (user.status == UserStatus.ACTIVE) "✅" else "⏳"
                    val statusText = if (user.status == UserStatus.ACTIVE) "активен" else "приглашён"
                    appendLine("$statusEmoji @${user.username} - $statusText")
                }
            }

        reply(message, text)
    }
}
