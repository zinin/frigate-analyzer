package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StartCommandHandler(
    private val userService: TelegramUserService,
    private val properties: TelegramProperties,
    private val eventPublisher: ApplicationEventPublisher,
) : CommandHandler {
    override val command: String = "start"
    override val description: String = "Начать работу с ботом"
    override val requiredRole: UserRole? = null
    override val order: Int = 1

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val privateMessage = message as? PrivateContentMessage<*>
        val username = privateMessage?.user?.username?.withoutAt
        if (username == null) {
            reply(message, "Ошибка: не удалось определить ваш username.")
            return
        }

        val chatId = message.chat.id.chatId.long
        val userId =
            privateMessage
                .user
                .id
                .chatId
                .long

        if (username == properties.owner) {
            val existing = userService.findByUsername(username)
            if (existing == null) {
                userService.inviteUser(username)
            }
            if (existing?.status != UserStatus.ACTIVE) {
                userService.activateUser(
                    username = username,
                    chatId = chatId,
                    userId = userId,
                    firstName = privateMessage.user.firstName,
                    lastName = privateMessage.user.lastName,
                )
            }

            reply(message, "Добро пожаловать, владелец! Используйте /help для списка команд.")
            eventPublisher.publishEvent(OwnerActivatedEvent(chatId))
            return
        }

        val user = userService.findByUsername(username)
        if (user == null) {
            reply(message, properties.unauthorizedMessage)
            return
        }

        if (user.status == UserStatus.ACTIVE) {
            reply(message, "Вы уже подписаны на уведомления. Используйте /help для списка команд.")
            return
        }

        val activated =
            userService.activateUser(
                username = username,
                chatId = chatId,
                userId = userId,
                firstName = privateMessage.user.firstName,
                lastName = privateMessage.user.lastName,
            )

        if (activated != null) {
            reply(message, "Вы успешно подписались на уведомления! Используйте /help для списка команд.")
        } else {
            reply(message, "Ошибка активации. Обратитесь к администратору.")
        }
    }
}
