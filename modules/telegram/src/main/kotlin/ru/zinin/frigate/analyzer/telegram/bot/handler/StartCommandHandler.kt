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
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StartCommandHandler(
    private val userService: TelegramUserService,
    private val properties: TelegramProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val msg: MessageResolver,
) : CommandHandler {
    override val command: String = "start"
    override val description: String = "Start"
    override val requiredRole: UserRole? = null
    override val order: Int = 1

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val privateMessage = message as? PrivateContentMessage<*>
        val username = privateMessage?.user?.username?.withoutAt
        val telegramLang = privateMessage?.user?.ietfLanguageCode?.code

        if (username == null) {
            val lang = detectLanguage(telegramLang)
            reply(message, msg.get("command.start.error.username", lang))
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
                val detectedLang = detectLanguage(telegramLang)
                userService.activateUser(
                    username = username,
                    chatId = chatId,
                    userId = userId,
                    firstName = privateMessage.user.firstName,
                    lastName = privateMessage.user.lastName,
                )
                userService.updateLanguage(chatId, detectedLang)
            }

            val lang = userService.getUserLanguage(chatId)
            reply(message, msg.get("command.start.welcome.owner", lang))
            eventPublisher.publishEvent(OwnerActivatedEvent(chatId))
            return
        }

        val existingUser = userService.findByUsername(username)
        if (existingUser == null) {
            val lang = detectLanguage(telegramLang)
            reply(message, msg.get("common.error.unauthorized", lang))
            return
        }

        if (existingUser.status == UserStatus.ACTIVE) {
            val lang = userService.getUserLanguage(chatId)
            reply(message, msg.get("command.start.already.subscribed", lang))
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
            val detectedLang = detectLanguage(telegramLang)
            userService.updateLanguage(chatId, detectedLang)
            val lang = userService.getUserLanguage(chatId)
            reply(message, msg.get("command.start.subscribed", lang))
        } else {
            val lang = detectLanguage(telegramLang)
            reply(message, msg.get("command.start.error.activation", lang))
        }
    }

    companion object {
        internal fun detectLanguage(telegramLanguageCode: String?): String =
            if (telegramLanguageCode?.startsWith("en") == true) "en" else "ru"
    }
}
