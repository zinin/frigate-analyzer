package ru.zinin.frigate.analyzer.telegram.bot

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.chat.CommonUser
import dev.inmo.tgbotapi.types.commands.BotCommandScopeChat
import dev.inmo.tgbotapi.types.commands.BotCommandScopeDefault
import dev.inmo.tgbotapi.types.message.abstracts.ChatContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.OwnerActivatedEvent
import ru.zinin.frigate.analyzer.telegram.bot.handler.StartCommandHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings.AiSettingsCallbackHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings.AiSettingsCallbacks
import ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings.AiSettingsMessageRenderer
import ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings.AiSettingsViewStateFactory
import ru.zinin.frigate.analyzer.telegram.bot.handler.cancel.CancelExportHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsMessageRenderer
import ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsSettingsCallbackHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsViewStateFactory
import ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.ScheduleCallbackHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.ScheduleSettingsFlow
import ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandler
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.filter.AuthResult
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.impl.TelegramUserServiceImpl

private val logger = KotlinLogging.logger {}

private fun ChatContentMessage<*>.telegramLanguageCode(): String? =
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
    private val notificationsViewStateFactory: NotificationsViewStateFactory,
    private val scheduleSettingsFlow: ScheduleSettingsFlow,
    // Экран `/ai` — через ObjectProvider: он вырезается вместе с фичей описаний, а бот обязан
    // стартовать и без него. Отсутствие любого из трёх гасит спиннер и ничего не меняет.
    private val aiSettingsCallbackHandler: ObjectProvider<AiSettingsCallbackHandler>,
    private val aiSettingsMessageRenderer: ObjectProvider<AiSettingsMessageRenderer>,
    private val aiSettingsViewStateFactory: ObjectProvider<AiSettingsViewStateFactory>,
    private val msg: MessageResolver,
) {
    private val eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val sortedHandlers: List<CommandHandler>
        get() = handlers.sortedWith(compareBy<CommandHandler> { it.order }.thenBy { it.command })

    /**
     * Wires command, callback, and event handlers onto [context]; suspends only for the duration
     * of registration and returns once all routes are bound. The caller owns the polling Job —
     * see [ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisor].
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun registerRoutes(context: BehaviourContext) =
        with(context) {
            // Note: `bot` inside this block resolves to BehaviourContext.bot (same TelegramBot
            // instance as this@FrigateAnalyzerBot.bot via constructor injection). The implicit
            // receiver here is BehaviourContext.
            sortedHandlers.forEach { handler ->
                onCommand(handler.command, requireOnlyCommandInMessage = false) { message ->
                    val resolvedUser: TelegramUserDto? =
                        if (handler.requiredRole != null) {
                            when (val result = authorizationFilter.authorize(message)) {
                                AuthResult.Unauthorized -> {
                                    val lang = StartCommandHandler.detectLanguage(message.telegramLanguageCode())
                                    reply(message, msg.get("common.error.unauthorized", lang))
                                    return@onCommand
                                }

                                AuthResult.NeedsActivation -> {
                                    val lang = StartCommandHandler.detectLanguage(message.telegramLanguageCode())
                                    reply(message, msg.get("common.error.activation.required", lang))
                                    return@onCommand
                                }

                                is AuthResult.Active -> {
                                    if (handler.requiredRole == UserRole.OWNER && result.role != UserRole.OWNER) {
                                        val lang =
                                            result.user.languageCode
                                                ?: StartCommandHandler.detectLanguage(message.telegramLanguageCode())
                                        reply(message, msg.get("common.error.owner.only", lang))
                                        return@onCommand
                                    }
                                    result.user
                                }
                            }
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

            // markerFactory = null (the library's documented opt-out) makes nfs: callbacks of one
            // user run in parallel instead of one-at-a-time. Required because `nfs:g:sched:zman`
            // starts a 120 s waiter INSIDE this handler: with the default per-user marker that
            // waiter froze every later nfs: click and then replayed it late. Do NOT "clean this
            // up" — the two registrations above keep the default on purpose (no waiter there, so
            // their serialization is free double-click protection). Parallel nfs: handling keeps
            // state WELL-FORMED: every payload carries an explicit value (:1 / :0, never a toggle)
            // and is idempotent, RERENDER re-reads state from the DB, and the write-order
            // invariant (window → zone → enabled LAST) is sequenced within each writer, so
            // "enabled without a window" stays unreachable. What it does NOT preserve is click
            // ORDER: two conflicting clicks milliseconds apart may commit in either order, and
            // their two edits may reach Telegram out of order, leaving the keyboard one step
            // behind until the next render. Accepted — single owner, single instance, and the
            // window is milliseconds. Double `zman` is guarded by ActiveZoneInputTracker instead.
            onDataCallbackQuery(
                initialFilter = { it.data.startsWith("nfs:") },
                markerFactory = null,
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
                    // nfs:-callback намеренно использует findActiveByUsername напрямую, а не authorize(...):
                    // рассылка уведомлений идёт только на ACTIVE chatId-ы (getAllActiveChatIds), поэтому
                    // callback физически не может прийти от INVITED/Unauthorized пользователя.
                    val current = userService.findActiveByUsername(senderUsername) ?: return@onDataCallbackQuery
                    val cid = current.chatId ?: return@onDataCallbackQuery
                    val owner = userService.isOwner(current.username)
                    // Routing invariant: the sched subtree MUST be intercepted BEFORE the generic
                    // notificationsSettingsCallbackHandler.dispatch (which silently IGNOREs it).
                    if (callback.data.startsWith(ScheduleCallbackHandler.PREFIX)) {
                        @Suppress("UNCHECKED_CAST")
                        with(scheduleSettingsFlow) {
                            handle(callback.data, callbackMsg as ContentMessage<TextContent>, current, owner)
                        }
                        return@onDataCallbackQuery
                    }
                    val outcome = notificationsSettingsCallbackHandler.dispatch(callback.data, cid, owner, current)
                    when (outcome) {
                        NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER -> {
                            val updated = userService.findByChatIdAsDto(cid) ?: current
                            val state = notificationsViewStateFactory.build(updated, owner)
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

            // Дефолтный markerFactory, в отличие от блока nfs: выше: waiter-а здесь нет, а
            // сериализация кликов одного пользователя бесплатно защищает от двойного нажатия —
            // ровно по этой причине её сохранили quick-export и cancel.
            onDataCallbackQuery(
                initialFilter = { it.data.startsWith(AiSettingsCallbacks.PREFIX) },
            ) { callback ->
                handleAiSettingsCallback(callback)
            }

            onContentMessage { message ->
                val textContent = message.content as? TextContent
                if (textContent?.text?.startsWith("/") == true) {
                    return@onContentMessage
                }

                val lang = StartCommandHandler.detectLanguage(message.telegramLanguageCode())
                when (authorizationFilter.authorize(message)) {
                    AuthResult.Unauthorized -> reply(message, msg.get("common.error.unauthorized", lang))
                    AuthResult.NeedsActivation -> reply(message, msg.get("common.error.activation.required", lang))
                    is AuthResult.Active -> Unit
                }
            }
        }

    /**
     * Один клик по экрану `/ai`. Регистрация выше остаётся тонкой намеренно: всё, что решает,
     * ЧЕМ ответить, живёт в [AiSettingsCallbackHandler.classify] и потому проверяется тестами, а
     * не поведенческим билдером ktgbotapi.
     *
     * Инвариант: ответ на коллбэк уходит ровно один раз на КАЖДОМ исходе, включая отсутствие
     * бинов и отказ БД. Неотвеченный коллбэк снимет только таймаут Telegram, а спиннер до тех пор
     * висит на кнопке. Порядок «ответ → запись → перерисовка» держит
     * [AiSettingsCallbackHandler.handle]; его обоснование — там же.
     */
    private suspend fun handleAiSettingsCallback(callback: DataCallbackQuery) {
        val handler = aiSettingsCallbackHandler.getIfAvailable()
        val renderer = aiSettingsMessageRenderer.getIfAvailable()
        val sender = resolveAiSettingsSender(callback)
        // Язык неизвестного отправителя не важен: ему отвечают без текста.
        val lang = sender?.languageCode ?: "en"
        try {
            if (sender == null || handler == null) {
                val outcome =
                    if (sender == null) {
                        AiSettingsCallbackHandler.DispatchOutcome.UNAUTHORIZED
                    } else {
                        AiSettingsCallbackHandler.DispatchOutcome.IGNORE
                    }
                answerAiSettingsCallback(callback, AiSettingsCallbackHandler.Dispatched(outcome), renderer, lang)
                return
            }
            val dispatched =
                handler.handle(callback.data, userService.isOwner(sender.username), sender.username) { dispatched ->
                    answerAiSettingsCallback(callback, dispatched, renderer, lang)
                }

            @Suppress("UNCHECKED_CAST")
            val screen = (callback as? MessageDataCallbackQuery)?.message as? ContentMessage<TextContent> ?: return
            when (dispatched.outcome) {
                // ALERT, UNAUTHORIZED и IGNORE экрана не трогают: ответ им уже ушёл.
                AiSettingsCallbackHandler.DispatchOutcome.RERENDER -> rerenderAiSettings(screen, lang, callback)

                AiSettingsCallbackHandler.DispatchOutcome.CLOSE -> closeAiSettingsKeyboard(screen)

                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to handle aip callback data=${callback.data}" }
        }
    }

    /**
     * Перерисовка после записи — единственное подтверждение переключения, поэтому состояние
     * читается заново: две открытые копии экрана сходятся к одной картине.
     */
    private suspend fun rerenderAiSettings(
        screen: ContentMessage<TextContent>,
        lang: String,
        callback: DataCallbackQuery,
    ) {
        val renderer = aiSettingsMessageRenderer.getIfAvailable() ?: return
        val viewStateFactory = aiSettingsViewStateFactory.getIfAvailable() ?: return
        val rendered = renderer.render(viewStateFactory.build(lang))
        try {
            bot.editMessageText(screen, rendered.text, replyMarkup = rendered.keyboard)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val isNotModified = e.message?.contains("message is not modified", ignoreCase = true) == true
            if (isNotModified) {
                logger.debug { "aip edit no-op (message not modified): ${callback.data}" }
            } else {
                logger.warn(e) { "Failed to edit /ai message for callback=${callback.data}" }
            }
        }
    }

    private suspend fun closeAiSettingsKeyboard(screen: ContentMessage<TextContent>) {
        try {
            bot.editMessageReplyMarkup(screen, replyMarkup = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to close /ai keyboard" }
        }
    }

    /**
     * Единственный ответ на `aip:`-коллбэк. Не бросает: отказ Telegram на ответе не повод отменять
     * выбор владельца, который в этот момент ещё не записан.
     *
     * Модалка, а не тост, только у ALERT: причина недоступности пресета — единственное место, где
     * владелец её узнаёт, а тост в углу легко пропустить. У успешного переключения текста нет
     * вовсе: ответ уходит до записи, поэтому «пресет X активен» было бы обещанием, данным до
     * факта; подтверждает его перерисовка.
     */
    private suspend fun answerAiSettingsCallback(
        callback: DataCallbackQuery,
        dispatched: AiSettingsCallbackHandler.Dispatched,
        renderer: AiSettingsMessageRenderer?,
        lang: String,
    ) {
        try {
            bot.answer(
                callback,
                text = dispatched.alertKey?.let { key -> renderer?.alertText(key, dispatched.alertCause, lang) },
                showAlert = dispatched.outcome == AiSettingsCallbackHandler.DispatchOutcome.ALERT,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to answer aip callback id=${callback.id}" }
        }
    }

    /** null = отправителя нет среди активных пользователей или БД не ответила. */
    private suspend fun resolveAiSettingsSender(callback: DataCallbackQuery): TelegramUserDto? =
        try {
            callback.user.username
                ?.withoutAt
                ?.let { userService.findActiveByUsername(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to resolve the sender of aip callback data=${callback.data}" }
            null
        }

    @EventListener
    fun onOwnerActivated(event: OwnerActivatedEvent) {
        eventScope.launch {
            registerOwnerCommands(event.chatId) // keep direct chatId — no DB round-trip
        }
    }

    suspend fun registerDefaultCommands() {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to register default bot commands" }
        }
    }

    suspend fun registerOwnerCommandsIfPossible() {
        try {
            // Case-insensitive lookup so owner-command registration survives a casing
            // difference between `TELEGRAM_OWNER` env and the DB-stored username.
            val owner =
                userService
                    .findByUsernameIgnoreCase(properties.owner)
                    ?.takeIf { it.status == UserStatus.ACTIVE }
            if (owner?.chatId != null) {
                registerOwnerCommands(owner.chatId)
            }
        } catch (e: CancellationException) {
            // Rethrow cancellation so the supervisor's shutdown is responsive — `Exception`
            // catches CancellationException in Kotlin coroutines.
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to look up owner for command registration" }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to register owner bot commands for chat $chatId" }
        }
    }

    @PreDestroy
    fun stop() {
        logger.info { "Stopping Telegram bot event scope..." }
        eventScope.cancel()
        logger.info { "Telegram bot event scope stopped" }
    }
}
