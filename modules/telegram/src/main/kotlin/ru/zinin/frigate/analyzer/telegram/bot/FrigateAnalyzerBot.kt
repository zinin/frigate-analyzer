package ru.zinin.frigate.analyzer.telegram.bot

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.media.sendVideo
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.commands.BotCommandScopeChat
import dev.inmo.tgbotapi.types.commands.BotCommandScopeDefault
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress.Stage
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

private data class ExportDialogResult(
    val startInstant: Instant,
    val endInstant: Instant,
    val camId: String,
    val mode: ExportMode,
)

private fun renderProgress(
    stage: Stage,
    percent: Int? = null,
    mode: ExportMode = ExportMode.ORIGINAL,
    compressing: Boolean = false,
): String {
    val stages =
        buildList {
            add(Stage.PREPARING to "Подготовка")
            add(Stage.MERGING to "Склейка видео")
            if (compressing) add(Stage.COMPRESSING to "Сжатие видео")
            if (mode == ExportMode.ANNOTATED) add(Stage.ANNOTATING to "Аннотация видео")
            add(Stage.SENDING to "Отправка")
            add(Stage.DONE to "Готово")
        }

    val currentIndex = stages.indexOfFirst { it.first == stage }

    return buildString {
        for ((index, pair) in stages.withIndex()) {
            val (s, label) = pair
            when {
                s == stage && s == Stage.DONE -> appendLine("\u2705 $label")
                s == stage && s == Stage.ANNOTATING && percent != null -> appendLine("\uD83D\uDD04 $label: $percent%")
                s == stage -> appendLine("\uD83D\uDD04 $label...")
                currentIndex >= 0 && index < currentIndex -> appendLine("\u2705 $label")
                else -> appendLine("\u2B1C $label")
            }
        }
    }.trimEnd()
}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class FrigateAnalyzerBot(
    private val bot: TelegramBot,
    private val authorizationFilter: AuthorizationFilter,
    private val userService: TelegramUserService,
    private val properties: TelegramProperties,
    private val videoExportService: VideoExportService,
    private val clock: Clock,
) {
    private val botScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val EXPORT_DIALOG_TIMEOUT_MS = 600_000L
        private const val EXPORT_PROCESSING_TIMEOUT_MS = 300_000L
        private const val MAX_EXPORT_DURATION_MINUTES = 5L

        private val DEFAULT_COMMANDS =
            listOf(
                BotCommand("start", "Начать работу с ботом"),
                BotCommand("help", "Помощь"),
                BotCommand("export", "Выгрузить видео"),
                BotCommand("timezone", "Часовой пояс"),
            )

        private val OWNER_COMMANDS =
            DEFAULT_COMMANDS +
                listOf(
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

                        onCommand("export") { message ->
                            handleExport(message)
                        }

                        onCommand("timezone") { message ->
                            handleTimezone(message)
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
                appendLine("/export - Выгрузить видео с камеры")
                appendLine("/timezone - Настроить часовой пояс")

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

    @Suppress("LongMethod")
    private suspend fun BehaviourContext.handleTimezone(message: CommonMessage<TextContent>) {
        val role = authorizationFilter.getRole(message)
        if (role == null) {
            bot.reply(message, authorizationFilter.getUnauthorizedMessage())
            return
        }

        val chatId = message.chat.id
        val currentZone = userService.getUserZone(chatId.chatId.long)

        val tzKeyboard =
            InlineKeyboardMarkup(
                keyboard =
                    matrix {
                        row {
                            +CallbackDataInlineKeyboardButton("Калининград (UTC+2)", "tz:Europe/Kaliningrad")
                            +CallbackDataInlineKeyboardButton("Москва (UTC+3)", "tz:Europe/Moscow")
                        }
                        row {
                            +CallbackDataInlineKeyboardButton("Екатеринбург (UTC+5)", "tz:Asia/Yekaterinburg")
                            +CallbackDataInlineKeyboardButton("Омск (UTC+6)", "tz:Asia/Omsk")
                        }
                        row {
                            +CallbackDataInlineKeyboardButton("Красноярск (UTC+7)", "tz:Asia/Krasnoyarsk")
                            +CallbackDataInlineKeyboardButton("Иркутск (UTC+8)", "tz:Asia/Irkutsk")
                        }
                        row {
                            +CallbackDataInlineKeyboardButton("Якутск (UTC+9)", "tz:Asia/Yakutsk")
                            +CallbackDataInlineKeyboardButton("Владивосток (UTC+10)", "tz:Asia/Vladivostok")
                        }
                        row {
                            +CallbackDataInlineKeyboardButton("Ввести вручную", "tz:manual")
                            +CallbackDataInlineKeyboardButton("Отмена", "tz:cancel")
                        }
                    },
            )

        val tzSentMessage =
            sendTextMessage(chatId, "Ваш текущий часовой пояс: $currentZone\nВыберите часовой пояс:", replyMarkup = tzKeyboard)

        val callback =
            waitDataCallbackQuery()
                .filter {
                    it.data.startsWith("tz:") &&
                        (it as? MessageDataCallbackQuery)?.message?.let { msg ->
                            msg.messageId == tzSentMessage.messageId && msg.chat.id == chatId
                        } == true
                }.first()
        answer(callback)

        when {
            callback.data == "tz:cancel" -> {
                sendTextMessage(chatId, "Отменено.")
            }

            callback.data == "tz:manual" -> {
                sendTextMessage(chatId, "Введите Olson ID часового пояса (например: Europe/Moscow, Asia/Tokyo):")
                val inputMsg =
                    waitTextMessage()
                        .filter { it.chat.id == chatId }
                        .first()
                val input = inputMsg.content.text.trim()
                if (input == "/cancel") {
                    sendTextMessage(chatId, "Отменено.")
                    return
                }
                if (!input.contains('/')) {
                    sendTextMessage(chatId, "Пожалуйста, используйте формат Continent/City (например: Europe/Moscow).")
                    return
                }
                try {
                    val zone = ZoneId.of(input)
                    if (!userService.updateTimezone(chatId.chatId.long, zone.id)) {
                        sendTextMessage(chatId, "Ошибка сохранения часового пояса.")
                        return
                    }
                    val offset = zone.rules.getOffset(Instant.now(clock))
                    sendTextMessage(chatId, "Часовой пояс сохранён: ${zone.id} (UTC$offset)")
                } catch (e: DateTimeException) {
                    sendTextMessage(chatId, "Неизвестный часовой пояс. Попробуйте снова или выберите из списка.")
                }
            }

            else -> {
                val olsonCode = callback.data.removePrefix("tz:")
                try {
                    val zone = ZoneId.of(olsonCode)
                    if (!userService.updateTimezone(chatId.chatId.long, olsonCode)) {
                        sendTextMessage(chatId, "Ошибка сохранения часового пояса.")
                        return
                    }
                    val offset = zone.rules.getOffset(Instant.now(clock))
                    sendTextMessage(chatId, "Часовой пояс сохранён: $olsonCode (UTC$offset)")
                } catch (e: DateTimeException) {
                    sendTextMessage(chatId, "Неизвестный часовой пояс. Попробуйте снова.")
                }
            }
        }
    }

    @Suppress("LongMethod")
    private suspend fun BehaviourContext.handleExport(message: CommonMessage<TextContent>) {
        val role = authorizationFilter.getRole(message)
        if (role == null) {
            bot.reply(message, authorizationFilter.getUnauthorizedMessage())
            return
        }

        val chatId = message.chat.id
        val userZone = userService.getUserZone(chatId.chatId.long)

        var userNotified = false

        val dialogResult =
            withTimeoutOrNull(EXPORT_DIALOG_TIMEOUT_MS) {
                // Step 1: Date selection
                val dateKeyboard =
                    InlineKeyboardMarkup(
                        keyboard =
                            matrix {
                                row {
                                    +CallbackDataInlineKeyboardButton("Сегодня", "export:today")
                                    +CallbackDataInlineKeyboardButton("Вчера", "export:yesterday")
                                }
                                row {
                                    +CallbackDataInlineKeyboardButton("Ввести дату", "export:custom")
                                    +CallbackDataInlineKeyboardButton("Отмена", "export:cancel")
                                }
                            },
                    )

                val dateSentMessage = sendTextMessage(chatId, "Выберите дату:", replyMarkup = dateKeyboard)

                val dateCallback =
                    waitDataCallbackQuery()
                        .filter {
                            it.data.startsWith("export:") &&
                                (it as? MessageDataCallbackQuery)?.message?.let { msg ->
                                    msg.messageId == dateSentMessage.messageId && msg.chat.id == chatId
                                } == true
                        }.first()
                answer(dateCallback)

                if (dateCallback.data == "export:cancel") {
                    sendTextMessage(chatId, "Экспорт отменён.")
                    userNotified = true
                    return@withTimeoutOrNull null
                }

                val date: LocalDate =
                    when (dateCallback.data) {
                        "export:today" -> {
                            Instant.now(clock).atZone(userZone).toLocalDate()
                        }

                        "export:yesterday" -> {
                            Instant
                                .now(clock)
                                .atZone(userZone)
                                .toLocalDate()
                                .minusDays(1)
                        }

                        "export:custom" -> {
                            sendTextMessage(chatId, "Введите дату (формат: YYYY-MM-DD) или /cancel для отмены:")
                            val dateMsg =
                                waitTextMessage()
                                    .filter { it.chat.id == chatId }
                                    .first()
                            val dateInput = dateMsg.content.text.trim()
                            if (dateInput == "/cancel" || dateInput.equals("отмена", ignoreCase = true)) {
                                sendTextMessage(chatId, "Экспорт отменён.")
                                userNotified = true
                                return@withTimeoutOrNull null
                            }
                            try {
                                LocalDate.parse(dateInput)
                            } catch (e: DateTimeParseException) {
                                sendTextMessage(chatId, "Неверный формат даты. Используйте YYYY-MM-DD. Экспорт отменён.")
                                userNotified = true
                                return@withTimeoutOrNull null
                            }
                        }

                        else -> {
                            return@withTimeoutOrNull null
                        }
                    }

                // Step 2: Time range input
                sendTextMessage(
                    chatId,
                    "Введите диапазон времени (например: 9:15-9:20, макс. 5 минут)\nВремя в вашем часовом поясе: $userZone\nИли /cancel:",
                )
                val timeMsg =
                    waitTextMessage()
                        .filter { it.chat.id == chatId }
                        .first()

                val timeInput = timeMsg.content.text.trim()
                if (timeInput == "/cancel" || timeInput.equals("отмена", ignoreCase = true)) {
                    sendTextMessage(chatId, "Экспорт отменён.")
                    userNotified = true
                    return@withTimeoutOrNull null
                }

                val timeRange = parseTimeRange(timeInput)
                if (timeRange == null) {
                    sendTextMessage(chatId, "Неверный формат. Используйте H:MM-H:MM (например, 9:15-9:20). Экспорт отменён.")
                    userNotified = true
                    return@withTimeoutOrNull null
                }

                val (startTime, endTime) = timeRange
                val durationMinutes = ChronoUnit.MINUTES.between(startTime, endTime)
                if (durationMinutes > MAX_EXPORT_DURATION_MINUTES || durationMinutes <= 0) {
                    sendTextMessage(
                        chatId,
                        "Диапазон должен быть от 1 до $MAX_EXPORT_DURATION_MINUTES минут. Экспорт отменён.",
                    )
                    userNotified = true
                    return@withTimeoutOrNull null
                }

                // Convert to Instant using user's timezone
                val startInstant = LocalDateTime.of(date, startTime).atZone(userZone).toInstant()
                val endInstant = LocalDateTime.of(date, endTime).atZone(userZone).toInstant()

                // DST guard: validate duration after timezone conversion
                val actualDuration = Duration.between(startInstant, endInstant)
                if (actualDuration.isNegative || actualDuration.isZero || actualDuration.toMinutes() > MAX_EXPORT_DURATION_MINUTES) {
                    sendTextMessage(
                        chatId,
                        "Диапазон после конвертации в UTC превышает 5 минут " +
                            "(возможно из-за перехода на летнее/зимнее время). Попробуйте другой диапазон.",
                    )
                    userNotified = true
                    return@withTimeoutOrNull null
                }

                // Step 3: Camera selection
                val cameras = videoExportService.findCamerasWithRecordings(startInstant, endInstant)
                if (cameras.isEmpty()) {
                    sendTextMessage(chatId, "Записей за $date $startTime-$endTime не найдено.")
                    userNotified = true
                    return@withTimeoutOrNull null
                }

                val cameraKeyboard =
                    InlineKeyboardMarkup(
                        keyboard =
                            matrix {
                                cameras.forEach { cam ->
                                    row {
                                        +CallbackDataInlineKeyboardButton(
                                            "${cam.camId} (${cam.recordingsCount})",
                                            "export:cam:${cam.camId}",
                                        )
                                    }
                                }
                                row {
                                    +CallbackDataInlineKeyboardButton("Отмена", "export:cancel")
                                }
                            },
                    )

                val camSentMessage = sendTextMessage(chatId, "Выберите камеру:", replyMarkup = cameraKeyboard)

                val camCallback =
                    waitDataCallbackQuery()
                        .filter {
                            (it.data.startsWith("export:cam:") || it.data == "export:cancel") &&
                                (it as? MessageDataCallbackQuery)?.message?.let { msg ->
                                    msg.messageId == camSentMessage.messageId && msg.chat.id == chatId
                                } == true
                        }.first()
                answer(camCallback)

                if (camCallback.data == "export:cancel") {
                    sendTextMessage(chatId, "Экспорт отменён.")
                    userNotified = true
                    return@withTimeoutOrNull null
                }

                val camId = camCallback.data.removePrefix("export:cam:")

                // Step 4: Mode selection
                val modeKeyboard =
                    InlineKeyboardMarkup(
                        keyboard =
                            matrix {
                                row {
                                    +CallbackDataInlineKeyboardButton("Оригинал", "export:mode:original")
                                    +CallbackDataInlineKeyboardButton("С объектами", "export:mode:annotated")
                                }
                                row {
                                    +CallbackDataInlineKeyboardButton("Отмена", "export:cancel")
                                }
                            },
                    )

                val modeSentMessage = sendTextMessage(chatId, "Выберите режим экспорта:", replyMarkup = modeKeyboard)

                val modeCallback =
                    waitDataCallbackQuery()
                        .filter {
                            (it.data.startsWith("export:mode:") || it.data == "export:cancel") &&
                                (it as? MessageDataCallbackQuery)?.message?.let { msg ->
                                    msg.messageId == modeSentMessage.messageId && msg.chat.id == chatId
                                } == true
                        }.first()
                answer(modeCallback)

                if (modeCallback.data == "export:cancel") {
                    sendTextMessage(chatId, "Экспорт отменён.")
                    userNotified = true
                    return@withTimeoutOrNull null
                }

                val mode =
                    when (modeCallback.data) {
                        "export:mode:annotated" -> ExportMode.ANNOTATED
                        else -> ExportMode.ORIGINAL
                    }

                ExportDialogResult(startInstant, endInstant, camId, mode)
            }

        if (dialogResult == null) {
            if (!userNotified) {
                sendTextMessage(chatId, "Время ожидания истекло. Попробуйте снова /export.")
            }
            return
        }

        val (startInstant, endInstant, camId, mode) = dialogResult

        // Step 5: Export video with progress (separate timeout from dialog)
        val statusMessage = sendTextMessage(chatId, renderProgress(Stage.PREPARING, mode = mode))

        var lastRenderedStage: Stage? = null
        var lastRenderedPercent: Int? = null
        var hadCompressing = false

        val onProgress: suspend (VideoExportProgress) -> Unit = { progress ->
            if (progress.stage == Stage.COMPRESSING) hadCompressing = true

            val shouldUpdate =
                when {
                    progress.stage != lastRenderedStage -> {
                        true
                    }

                    progress.stage == Stage.ANNOTATING && progress.percent != null -> {
                        val lastPct = lastRenderedPercent ?: -1
                        (progress.percent - lastPct) >= 5
                    }

                    else -> {
                        false
                    }
                }

            if (shouldUpdate) {
                lastRenderedStage = progress.stage
                lastRenderedPercent = progress.percent
                try {
                    editMessageText(
                        statusMessage,
                        renderProgress(progress.stage, progress.percent, mode, hadCompressing),
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }
            }
        }

        try {
            val videoPath =
                withTimeoutOrNull(EXPORT_PROCESSING_TIMEOUT_MS) {
                    videoExportService.exportVideo(startInstant, endInstant, camId, mode, onProgress)
                } ?: run {
                    sendTextMessage(chatId, "Обработка видео заняла слишком много времени. Попробуйте меньший диапазон.")
                    return
                }

            try {
                try {
                    editMessageText(statusMessage, renderProgress(Stage.SENDING, mode = mode, compressing = hadCompressing))
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }

                val localDate = startInstant.atZone(userZone).toLocalDate()
                val localStart = startInstant.atZone(userZone).toLocalTime()
                val localEnd = endInstant.atZone(userZone).toLocalTime()
                val fileName = "export_${camId}_${localDate}_$localStart-$localEnd.mp4".replace(":", "-")
                sendVideo(
                    chatId,
                    videoPath.toFile().asMultipartFile().copy(filename = fileName),
                )

                try {
                    editMessageText(statusMessage, renderProgress(Stage.DONE, mode = mode, compressing = hadCompressing))
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }
            } finally {
                try {
                    videoExportService.cleanupExportFile(videoPath)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to delete temp file: $videoPath" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Video export failed" }
            val errorText =
                if (mode == ExportMode.ANNOTATED) {
                    "Ошибка экспорта видео с объектами. Попробуйте обычный режим или другие параметры."
                } else {
                    "Ошибка экспорта видео. Попробуйте меньший диапазон или другую камеру."
                }
            sendTextMessage(chatId, errorText)
        }
    }

    private fun parseTimeRange(input: String): Pair<LocalTime, LocalTime>? {
        val parts = input.split("-", limit = 2)
        if (parts.size != 2) return null
        return try {
            val formatter = DateTimeFormatter.ofPattern("H:mm")
            val start = LocalTime.parse(parts[0].trim(), formatter)
            val end = LocalTime.parse(parts[1].trim(), formatter)
            start to end
        } catch (e: DateTimeParseException) {
            null
        }
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
