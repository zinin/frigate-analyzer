package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitTextMessage
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TimezoneCommandHandler(
    private val userService: TelegramUserService,
    private val clock: Clock,
) : CommandHandler {
    override val command: String = "timezone"
    override val description: String = "Часовой пояс"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 4

    @Suppress("LongMethod")
    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        role: UserRole?,
    ) {
        val chatId = message.chat.id
        val currentZone = userService.getUserZone(chatId.chatId.long)

        val completed =
            withTimeoutOrNull(TIMEZONE_DIALOG_TIMEOUT_MS) {
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
                            return@withTimeoutOrNull
                        }

                        if (!input.contains('/')) {
                            sendTextMessage(chatId, "Пожалуйста, используйте формат Continent/City (например: Europe/Moscow).")
                            return@withTimeoutOrNull
                        }

                        try {
                            val zone = ZoneId.of(input)
                            if (!userService.updateTimezone(chatId.chatId.long, zone.id)) {
                                sendTextMessage(chatId, "Ошибка сохранения часового пояса.")
                                return@withTimeoutOrNull
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
                                return@withTimeoutOrNull
                            }

                            val offset = zone.rules.getOffset(Instant.now(clock))
                            sendTextMessage(chatId, "Часовой пояс сохранён: $olsonCode (UTC$offset)")
                        } catch (e: DateTimeException) {
                            sendTextMessage(chatId, "Неизвестный часовой пояс. Попробуйте снова.")
                        }
                    }
                }
            }

        if (completed == null) {
            sendTextMessage(chatId, "Время ожидания истекло. Попробуйте снова /timezone.")
        }
    }

    companion object {
        private const val TIMEZONE_DIALOG_TIMEOUT_MS = 120_000L
    }
}
