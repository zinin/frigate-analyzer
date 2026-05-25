package ru.zinin.frigate.analyzer.core.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.service.StatusService
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.impl.StatusMessageFormatter
import java.time.Clock
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StatusCommandHandler(
    private val statusService: StatusService,
    private val formatter: StatusMessageFormatter,
    private val userService: TelegramUserService,
    private val clock: Clock,
) : CommandHandler {
    override val command: String = "status"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 8

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val snapshot = statusService.collect()
        val zone = userService.getUserZone(message.chat.id.chatId.long)
        val language = user?.languageCode ?: "en"
        val text =
            formatter.format(
                snapshot = snapshot,
                language = language,
                zone = zone,
                now = Instant.now(clock),
            )
        sendTextMessage(
            message.chat,
            text,
            parseMode = HTMLParseMode,
            replyParameters = ReplyParameters(message.metaInfo),
        )
    }
}
