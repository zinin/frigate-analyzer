package ru.zinin.frigate.analyzer.telegram.bot

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.commands.BotCommandScopeChat
import dev.inmo.tgbotapi.types.commands.BotCommandScopeDefault
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.OwnerActivatedEvent
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class FrigateAnalyzerBot(
    private val bot: TelegramBot,
    private val authorizationFilter: AuthorizationFilter,
    private val userService: TelegramUserService,
    private val properties: TelegramProperties,
    private val handlers: List<CommandHandler>,
) {
    private val botScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val sortedHandlers: List<CommandHandler>
        get() = handlers.sortedWith(compareBy<CommandHandler> { it.order }.thenBy { it.command })

    private val defaultCommands: List<BotCommand>
        get() =
            sortedHandlers
                .filterNot { it.ownerOnly }
                .map { BotCommand(it.command, it.description) }

    private val ownerCommands: List<BotCommand>
        get() = sortedHandlers.map { BotCommand(it.command, it.description) }

    @PostConstruct
    fun start() {
        logger.info { "Starting Telegram bot with long polling..." }

        botScope.launch {
            try {
                val botInfo = bot.getMe()
                logger.info { "Bot started: ${botInfo.username} (${botInfo.firstName})" }

                registerDefaultCommands()

                try {
                    val owner = userService.findActiveByUsername(properties.owner)
                    if (owner?.chatId != null) {
                        registerOwnerCommands(owner.chatId)
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to look up owner for command registration" }
                }

                bot
                    .buildBehaviourWithLongPolling {
                        registerRoutes()
                    }.join()
            } catch (e: CancellationException) {
                logger.info { "Telegram bot long polling cancelled" }
            } catch (e: Exception) {
                logger.error(e) { "Error in Telegram bot long polling" }
            }
        }
    }

    private suspend fun BehaviourContext.registerRoutes() {
        sortedHandlers.forEach { handler ->
            onCommand(handler.command) { message ->
                val role: UserRole? =
                    if (handler.requiredRole != null) {
                        val resolvedRole = authorizationFilter.getRole(message)

                        if (resolvedRole == null) {
                            reply(message, authorizationFilter.getUnauthorizedMessage())
                            return@onCommand
                        }

                        if (handler.requiredRole == UserRole.OWNER && resolvedRole != UserRole.OWNER) {
                            reply(message, "Эта команда доступна только владельцу.")
                            return@onCommand
                        }

                        resolvedRole
                    } else {
                        null
                    }

                try {
                    with(handler) {
                        handle(message, role)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Error handling command /${handler.command}" }
                    reply(message, "Произошла ошибка. Попробуйте позже.")
                }
            }
        }

        onContentMessage { message ->
            val role = authorizationFilter.getRole(message)
            if (role == null) {
                reply(message, authorizationFilter.getUnauthorizedMessage())
                return@onContentMessage
            }
            // Ignore non-command messages for now
        }
    }

    @EventListener
    fun onOwnerActivated(event: OwnerActivatedEvent) {
        botScope.launch {
            registerOwnerCommands(event.chatId)
        }
    }

    private suspend fun registerDefaultCommands() {
        try {
            bot.setMyCommands(defaultCommands, scope = BotCommandScopeDefault)
            logger.info { "Default bot commands registered" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to register default bot commands" }
        }
    }

    private suspend fun registerOwnerCommands(chatId: Long) {
        try {
            bot.setMyCommands(ownerCommands, scope = BotCommandScopeChat(ChatId(RawChatId(chatId))))
            logger.info { "Owner bot commands registered for chat $chatId" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to register owner bot commands for chat $chatId" }
        }
    }

    @PreDestroy
    fun stop() {
        logger.info { "Stopping Telegram bot..." }
        botScope.cancel()
        logger.info { "Telegram bot stopped" }
    }
}
