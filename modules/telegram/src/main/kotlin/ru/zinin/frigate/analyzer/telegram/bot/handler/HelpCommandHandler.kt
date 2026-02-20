package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.model.UserRole

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class HelpCommandHandler(
    @Lazy private val handlers: List<CommandHandler>,
) : CommandHandler {
    override val command: String = "help"
    override val description: String = "Помощь"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 2

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        role: UserRole?,
    ) {
        val sortedHandlers = handlers.sortedWith(compareBy<CommandHandler> { it.order }.thenBy { it.command })
        val defaultCommands = sortedHandlers.filterNot { it.ownerOnly }
        val ownerCommands = sortedHandlers.filter { it.ownerOnly }

        val helpText =
            buildString {
                appendLine("📋 Доступные команды:")
                appendLine()

                defaultCommands.forEach { handler ->
                    appendLine("/${handler.command} - ${handler.description}")
                }

                if (role == UserRole.OWNER && ownerCommands.isNotEmpty()) {
                    appendLine()
                    appendLine("👑 Команды владельца:")
                    ownerCommands.forEach { handler ->
                        appendLine("/${handler.command} - ${handler.description}")
                    }
                }
            }

        reply(message, helpText)
    }
}
