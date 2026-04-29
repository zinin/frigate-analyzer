package ru.zinin.frigate.analyzer.telegram.bot

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.chat.CommonUser
import dev.inmo.tgbotapi.types.commands.BotCommandScopeChat
import dev.inmo.tgbotapi.types.commands.BotCommandScopeDefault
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
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
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.OwnerActivatedEvent
import ru.zinin.frigate.analyzer.telegram.bot.handler.StartCommandHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.cancel.CancelExportHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsMessageRenderer
import ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsSettingsCallbackHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandler
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.dto.NotificationsViewState
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.impl.TelegramUserServiceImpl

private val logger = KotlinLogging.logger {}

private fun CommonMessage<*>.telegramLanguageCode(): String? =
    ((this as? PrivateContentMessage<*>)?.user as? CommonUser)?.ietfLanguageCode?.code

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class FrigateAnalyzerBot(
    private val bot: TelegramBot,
    private val authorizationFilter: AuthorizationFilter,
    private val userService: TelegramUserService,
    private val properties: TelegramProperties,
    private val handlers: List<CommandHandler>,
    private val quickExportHandler: QuickExportHandler,
    private val cancelExportHandler: CancelExportHandler,
    private val notificationsSettingsCallbackHandler: NotificationsSettingsCallbackHandler,
    private val notificationsMessageRenderer: NotificationsMessageRenderer,
    private val appSettings: AppSettingsService,
    private val msg: MessageResolver,
) {
    private val botScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val sortedHandlers: List<CommandHandler>
        get() = handlers.sortedWith(compareBy<CommandHandler> { it.order }.thenBy { it.command })

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

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun BehaviourContext.registerRoutes() {
        sortedHandlers.forEach { handler ->
            onCommand(handler.command, requireOnlyCommandInMessage = false) { message ->
                val resolvedUser: TelegramUserDto? =
                    if (handler.requiredRole != null) {
                        val username = authorizationFilter.extractUsername(message)
                        if (username == null) {
                            val telegramLang = message.telegramLanguageCode()
                            val lang = StartCommandHandler.detectLanguage(telegramLang)
                            reply(message, msg.get("common.error.unauthorized", lang))
                            return@onCommand
                        }

                        val foundUser =
                            userService.findActiveByUsername(username)
                                ?: if (username == properties.owner) userService.findByUsername(username) else null
                        val resolvedRole =
                            when {
                                username == properties.owner -> UserRole.OWNER
                                foundUser != null -> UserRole.USER
                                else -> null
                            }

                        if (resolvedRole == null) {
                            val telegramLang = message.telegramLanguageCode()
                            val lang = StartCommandHandler.detectLanguage(telegramLang)
                            reply(message, msg.get("common.error.unauthorized", lang))
                            return@onCommand
                        }

                        if (handler.requiredRole == UserRole.OWNER && resolvedRole != UserRole.OWNER) {
                            val lang =
                                foundUser?.languageCode
                                    ?: StartCommandHandler.detectLanguage(
                                        message.telegramLanguageCode(),
                                    )
                            reply(message, msg.get("common.error.owner.only", lang))
                            return@onCommand
                        }

                        foundUser
                    } else {
                        null
                    }

                try {
                    with(handler) { handle(message, resolvedUser) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Error handling command /${handler.command}" }
                    val lang =
                        resolvedUser?.languageCode
                            ?: StartCommandHandler.detectLanguage(
                                message.telegramLanguageCode(),
                            )
                    reply(message, msg.get("common.error.generic", lang))
                }
            }
        }

        onDataCallbackQuery(
            initialFilter = {
                it.data.startsWith(QuickExportHandler.CALLBACK_PREFIX_ANNOTATED) ||
                    it.data.startsWith(QuickExportHandler.CALLBACK_PREFIX)
            },
        ) { callback ->
            try {
                quickExportHandler.handle(callback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error handling quick export callback: ${callback.data}" }
            }
        }

        onDataCallbackQuery(
            initialFilter = {
                it.data.startsWith(CancelExportHandler.CANCEL_PREFIX) ||
                    it.data.startsWith(CancelExportHandler.NOOP_PREFIX)
            },
        ) { callback ->
            try {
                cancelExportHandler.handle(callback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error handling cancel/noop callback: ${callback.data}" }
            }
        }

        onDataCallbackQuery(
            initialFilter = { it.data.startsWith("nfs:") },
        ) { callback ->
            // Acknowledge callback FIRST so Telegram clears the button spinner.
            try {
                bot.answer(callback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to answer nfs callback id=${callback.id}" }
            }

            try {
                val callbackMsg = (callback as? MessageDataCallbackQuery)?.message ?: return@onDataCallbackQuery
                val senderUsername = callback.user.username?.withoutAt ?: return@onDataCallbackQuery
                val current = userService.findByUsernameAsDto(senderUsername) ?: return@onDataCallbackQuery
                val cid = current.chatId ?: return@onDataCallbackQuery
                val owner = userService.isOwner(current.username)
                val outcome = notificationsSettingsCallbackHandler.dispatch(callback.data, cid, owner, current)
                when (outcome) {
                    NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER -> {
                        val updated = userService.findByChatIdAsDto(cid) ?: current
                        val state =
                            NotificationsViewState(
                                isOwner = owner,
                                recordingUserEnabled = updated.notificationsRecordingEnabled,
                                signalUserEnabled = updated.notificationsSignalEnabled,
                                recordingGlobalEnabled =
                                    if (owner) {
                                        appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true)
                                    } else {
                                        null
                                    },
                                signalGlobalEnabled =
                                    if (owner) {
                                        appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true)
                                    } else {
                                        null
                                    },
                                language = updated.languageCode ?: "en",
                            )
                        val rendered = notificationsMessageRenderer.render(state)
                        try {
                            @Suppress("UNCHECKED_CAST")
                            bot.editMessageText(
                                callbackMsg as ContentMessage<TextContent>,
                                rendered.text,
                                replyMarkup = rendered.keyboard,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val isNotModified =
                                e.message?.contains(
                                    "message is not modified",
                                    ignoreCase = true,
                                ) == true
                            if (isNotModified) {
                                logger.debug { "nfs edit no-op (message not modified): ${callback.data}" }
                            } else {
                                logger.warn(e) { "Failed to edit /notifications message for callback=${callback.data}" }
                            }
                        }
                    }

                    NotificationsSettingsCallbackHandler.DispatchOutcome.CLOSE -> {
                        try {
                            bot.editMessageReplyMarkup(callbackMsg, replyMarkup = null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to close /notifications keyboard" }
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to handle nfs callback data=${callback.data}" }
            }
        }

        onContentMessage { message ->
            val textContent = message.content as? TextContent
            if (textContent?.text?.startsWith("/") == true) {
                return@onContentMessage
            }

            val role = authorizationFilter.getRole(message)
            if (role == null) {
                val telegramLang = message.telegramLanguageCode()
                val lang = StartCommandHandler.detectLanguage(telegramLang)
                reply(message, msg.get("common.error.unauthorized", lang))
                return@onContentMessage
            }
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
            for (langCode in TelegramUserServiceImpl.SUPPORTED_LANGUAGES) {
                val commands =
                    sortedHandlers
                        .filterNot { it.ownerOnly }
                        .map { BotCommand(it.command, msg.get("command.${it.command}.description", langCode)) }
                bot.setMyCommands(commands, scope = BotCommandScopeDefault, languageCode = langCode)
            }
            // Default fallback for users with other languages
            val defaultCommands =
                sortedHandlers
                    .filterNot { it.ownerOnly }
                    .map { BotCommand(it.command, msg.get("command.${it.command}.description", "en")) }
            bot.setMyCommands(defaultCommands, scope = BotCommandScopeDefault)
            logger.info { "Default bot commands registered for all languages" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to register default bot commands" }
        }
    }

    private suspend fun registerOwnerCommands(chatId: Long) {
        try {
            val scope = BotCommandScopeChat(ChatId(RawChatId(chatId)))
            for (langCode in TelegramUserServiceImpl.SUPPORTED_LANGUAGES) {
                val commands =
                    sortedHandlers
                        .map { BotCommand(it.command, msg.get("command.${it.command}.description", langCode)) }
                bot.setMyCommands(commands, scope = scope, languageCode = langCode)
            }
            // Default fallback for users with other languages
            val defaultCommands =
                sortedHandlers
                    .map { BotCommand(it.command, msg.get("command.${it.command}.description", "en")) }
            bot.setMyCommands(defaultCommands, scope = scope)
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
