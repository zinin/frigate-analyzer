package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class HelpCommandHandler(
    @Lazy private val handlers: List<CommandHandler>,
    private val properties: TelegramProperties,
    private val msg: MessageResolver,
) : CommandHandler {
    override val command: String = "help"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 2

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val lang = user?.languageCode ?: "en"
        val sortedHandlers = handlers.sortedWith(compareBy<CommandHandler> { it.order }.thenBy { it.command })
        val defaultCommands = sortedHandlers.filterNot { it.ownerOnly }
        val ownerCommands = sortedHandlers.filter { it.ownerOnly }

        val helpText =
            buildString {
                appendLine(msg.get("command.help.header", lang))
                appendLine()

                defaultCommands.forEach { handler ->
                    appendLine("/${handler.command} - ${msg.get("command.${handler.command}.description", lang)}")
                }

                if (user?.username == properties.owner && ownerCommands.isNotEmpty()) {
                    appendLine()
                    appendLine(msg.get("command.help.owner.header", lang))
                    ownerCommands.forEach { handler ->
                        appendLine("/${handler.command} - ${msg.get("command.${handler.command}.description", lang)}")
                    }
                }
            }

        reply(message, helpText)
    }
}
