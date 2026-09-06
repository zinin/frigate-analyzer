package ru.zinin.frigate.analyzer.core.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import dev.inmo.tgbotapi.types.message.abstracts.ChatContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.NotificationVerdictService
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class VerdictsCommandHandler(
    private val verdictService: NotificationVerdictService,
    private val formatter: VerdictsMessageFormatter,
    private val userService: TelegramUserService,
    private val msg: MessageResolver,
) : CommandHandler {
    override val command: String = "verdicts"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 10

    override suspend fun BehaviourContext.handle(
        message: ChatContentMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val language = user?.languageCode ?: "en"
        val args = VerdictsArguments.parse(message.content.text)
        if (args == null) {
            reply(message, msg.get("verdicts.usage", language))
            return
        }
        val rows = verdictService.latest(args.camId, args.limit)
        val zone = userService.getUserZone(message.chat.id.chatId.long)
        sendTextMessage(
            message.chat,
            formatter.format(rows, language, zone),
            parseMode = HTMLParseMode,
            replyParameters = ReplyParameters(message.metaInfo),
        )
    }
}
