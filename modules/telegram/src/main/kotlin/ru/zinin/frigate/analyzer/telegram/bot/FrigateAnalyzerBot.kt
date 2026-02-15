package ru.zinin.frigate.analyzer.telegram.bot

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.commands.BotCommandScopeChat
import dev.inmo.tgbotapi.types.commands.BotCommandScopeDefault
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class FrigateAnalyzerBot(
    private val bot: TelegramBot,
    private val authorizationFilter: AuthorizationFilter,
    private val userService: TelegramUserService,
    private val properties: TelegramProperties,
) {
    private val botScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private val DEFAULT_COMMANDS = listOf(
            BotCommand("start", "Начать работу с ботом"),
            BotCommand("help", "Помощь"),
        )

        private val OWNER_COMMANDS = DEFAULT_COMMANDS + listOf(
            BotCommand("adduser", "Добавить пользователя"),
            BotCommand("removeuser", "Удалить пользователя"),
            BotCommand("users", "Список пользователей"),
        )
    }

    @PostConstruct
    fun start() {
        logger.info { "Starting Telegram bot with long polling..." }

        botScope.launch {
            try {
                val botInfo = bot.getMe()
                logger.info { "Bot started: ${botInfo.username} (${botInfo.firstName})" }

                registerDefaultCommands()

                val owner = userService.findActiveByUsername(properties.owner)
                if (owner?.chatId != null) {
                    registerOwnerCommands(owner.chatId)
                }

                bot
                    .buildBehaviourWithLongPolling {
                        onCommand("start") { message ->
                            handleStart(message)
                        }

                        onCommand("help") { message ->
                            handleHelp(message)
                        }

                        onCommand("adduser") { message ->
                            handleAddUser(message)
                        }

                        onCommand("removeuser") { message ->
                            handleRemoveUser(message)
                        }

                        onCommand("users") { message ->
                            handleUsers(message)
                        }

                        onContentMessage { message ->
                            val role = authorizationFilter.getRole(message)
                            if (role == null) {
                                reply(message, authorizationFilter.getUnauthorizedMessage())
                                return@onContentMessage
                            }
                            // Ignore non-command messages for now
                        }
                    }.join()
            } catch (e: Exception) {
                logger.error(e) { "Error in Telegram bot long polling" }
            }
        }
    }

    private suspend fun handleStart(message: CommonMessage<TextContent>) {
        val privateMessage = message as? PrivateContentMessage<*>
        val username = privateMessage?.user?.username?.withoutAt
        if (username == null) {
            bot.reply(message, "Ошибка: не удалось определить ваш username.")
            return
        }

        val chatId = message.chat.id.chatId.long
        val userId =
            privateMessage
                ?.user
                ?.id
                ?.chatId
                ?.long ?: return

        // Check if owner
        if (username == properties.owner) {
            // Register owner in DB for notifications
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
            bot.reply(message, "Добро пожаловать, владелец! Используйте /help для списка команд.")
            registerOwnerCommands(chatId)
            return
        }

        // Check if invited user
        val user = userService.findByUsername(username)
        if (user == null) {
            bot.reply(message, authorizationFilter.getUnauthorizedMessage())
            return
        }

        if (user.status == UserStatus.ACTIVE) {
            bot.reply(message, "Вы уже подписаны на уведомления. Используйте /help для списка команд.")
            return
        }

        // Activate invited user
        val activated =
            userService.activateUser(
                username = username,
                chatId = chatId,
                userId = userId,
                firstName = privateMessage.user.firstName,
                lastName = privateMessage.user.lastName,
            )

        if (activated != null) {
            bot.reply(message, "Вы успешно подписались на уведомления! Используйте /help для списка команд.")
        } else {
            bot.reply(message, "Ошибка активации. Обратитесь к администратору.")
        }
    }

    private suspend fun handleHelp(message: CommonMessage<TextContent>) {
        val role = authorizationFilter.getRole(message)
        if (role == null) {
            bot.reply(message, authorizationFilter.getUnauthorizedMessage())
            return
        }

        val helpText =
            buildString {
                appendLine("📋 Доступные команды:")
                appendLine()
                appendLine("/start - Активация подписки")
                appendLine("/help - Список команд")

                if (role == UserRole.OWNER) {
                    appendLine()
                    appendLine("👑 Команды владельца:")
                    appendLine("/adduser @username - Пригласить пользователя")
                    appendLine("/removeuser @username - Удалить пользователя")
                    appendLine("/users - Список пользователей")
                }
            }

        bot.reply(message, helpText)
    }

    private suspend fun handleAddUser(message: CommonMessage<TextContent>) {
        if (!authorizationFilter.isOwner(message)) {
            bot.reply(message, "Эта команда доступна только владельцу.")
            return
        }

        val text = message.content.text
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2) {
            bot.reply(message, "Использование: /adduser @username")
            return
        }

        val targetUsername = parts[1].trim().removePrefix("@")
        if (targetUsername.isBlank()) {
            bot.reply(message, "Использование: /adduser @username")
            return
        }

        if (targetUsername == properties.owner) {
            bot.reply(message, "Владелец не может быть добавлен как пользователь.")
            return
        }

        val existing = userService.findByUsername(targetUsername)
        if (existing != null) {
            val statusText = if (existing.status == UserStatus.ACTIVE) "активен" else "приглашён"
            bot.reply(message, "Пользователь @$targetUsername уже $statusText.")
            return
        }

        userService.inviteUser(targetUsername)
        bot.reply(message, "Пользователь @$targetUsername приглашён. Он должен написать /start боту для активации.")
    }

    private suspend fun handleRemoveUser(message: CommonMessage<TextContent>) {
        if (!authorizationFilter.isOwner(message)) {
            bot.reply(message, "Эта команда доступна только владельцу.")
            return
        }

        val text = message.content.text
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2) {
            bot.reply(message, "Использование: /removeuser @username")
            return
        }

        val targetUsername = parts[1].trim().removePrefix("@")
        if (targetUsername.isBlank()) {
            bot.reply(message, "Использование: /removeuser @username")
            return
        }

        if (targetUsername == properties.owner) {
            bot.reply(message, "Владелец не может быть удалён.")
            return
        }

        val removed = userService.removeUser(targetUsername)
        if (removed) {
            bot.reply(message, "Пользователь @$targetUsername удалён.")
        } else {
            bot.reply(message, "Пользователь @$targetUsername не найден.")
        }
    }

    private suspend fun handleUsers(message: CommonMessage<TextContent>) {
        if (!authorizationFilter.isOwner(message)) {
            bot.reply(message, "Эта команда доступна только владельцу.")
            return
        }

        val users = userService.getAllUsers()
        if (users.isEmpty()) {
            bot.reply(message, "Нет зарегистрированных пользователей.")
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

        bot.reply(message, text)
    }

    private suspend fun registerDefaultCommands() {
        try {
            bot.setMyCommands(DEFAULT_COMMANDS, scope = BotCommandScopeDefault)
            logger.info { "Default bot commands registered" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to register default bot commands" }
        }
    }

    private suspend fun registerOwnerCommands(chatId: Long) {
        try {
            bot.setMyCommands(OWNER_COMMANDS, scope = BotCommandScopeChat(ChatId(RawChatId(chatId))))
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
