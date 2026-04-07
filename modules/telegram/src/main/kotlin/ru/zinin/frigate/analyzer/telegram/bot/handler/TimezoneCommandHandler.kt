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
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
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
    private val msg: MessageResolver,
) : CommandHandler {
    override val command: String = "timezone"
    override val description: String = "Timezone"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 4

    @Suppress("LongMethod")
    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val lang = user?.languageCode ?: "ru"
        val chatId = message.chat.id
        val currentZone = userService.getUserZone(chatId.chatId.long)

        val completed =
            withTimeoutOrNull(TIMEZONE_DIALOG_TIMEOUT_MS) {
                val tzKeyboard =
                    InlineKeyboardMarkup(
                        keyboard =
                            matrix {
                                row {
                                    +CallbackDataInlineKeyboardButton(msg.get("command.timezone.zone.kaliningrad", lang), "tz:Europe/Kaliningrad")
                                    +CallbackDataInlineKeyboardButton(msg.get("command.timezone.zone.moscow", lang), "tz:Europe/Moscow")
                                }
                                row {
                                    +CallbackDataInlineKeyboardButton(msg.get("command.timezone.zone.yekaterinburg", lang), "tz:Asia/Yekaterinburg")
                                    +CallbackDataInlineKeyboardButton(msg.get("command.timezone.zone.omsk", lang), "tz:Asia/Omsk")
                                }
                                row {
                                    +CallbackDataInlineKeyboardButton(msg.get("command.timezone.zone.krasnoyarsk", lang), "tz:Asia/Krasnoyarsk")
                                    +CallbackDataInlineKeyboardButton(msg.get("command.timezone.zone.irkutsk", lang), "tz:Asia/Irkutsk")
                                }
                                row {
                                    +CallbackDataInlineKeyboardButton(msg.get("command.timezone.zone.yakutsk", lang), "tz:Asia/Yakutsk")
                                    +CallbackDataInlineKeyboardButton(msg.get("command.timezone.zone.vladivostok", lang), "tz:Asia/Vladivostok")
                                }
                                row {
                                    +CallbackDataInlineKeyboardButton(msg.get("command.timezone.manual.input", lang), "tz:manual")
                                    +CallbackDataInlineKeyboardButton(msg.get("common.cancel", lang), "tz:cancel")
                                }
                            },
                    )

                val tzSentMessage =
                    sendTextMessage(chatId, msg.get("command.timezone.current", lang, currentZone), replyMarkup = tzKeyboard)

                val callback =
                    waitDataCallbackQuery()
                        .filter {
                            it.data.startsWith("tz:") &&
                                (it as? MessageDataCallbackQuery)?.message?.let { cbMsg ->
                                    cbMsg.messageId == tzSentMessage.messageId && cbMsg.chat.id == chatId
                                } == true
                        }.first()
                answer(callback)

                when {
                    callback.data == "tz:cancel" -> {
                        sendTextMessage(chatId, msg.get("command.timezone.cancelled", lang))
                    }

                    callback.data == "tz:manual" -> {
                        sendTextMessage(chatId, msg.get("command.timezone.prompt.olson", lang))
                        val inputMsg =
                            waitTextMessage()
                                .filter { it.chat.id == chatId }
                                .first()
                        val input = inputMsg.content.text.trim()

                        if (input == "/cancel") {
                            sendTextMessage(chatId, msg.get("command.timezone.cancelled", lang))
                            return@withTimeoutOrNull
                        }

                        if (!input.contains('/')) {
                            sendTextMessage(chatId, msg.get("command.timezone.error.format", lang))
                            return@withTimeoutOrNull
                        }

                        try {
                            val zone = ZoneId.of(input)
                            if (!userService.updateTimezone(chatId.chatId.long, zone.id)) {
                                sendTextMessage(chatId, msg.get("command.timezone.error.save", lang))
                                return@withTimeoutOrNull
                            }

                            val offset = zone.rules.getOffset(Instant.now(clock))
                            sendTextMessage(chatId, msg.get("command.timezone.saved", lang, zone.id, offset))
                        } catch (e: DateTimeException) {
                            sendTextMessage(chatId, msg.get("command.timezone.error.unknown", lang))
                        }
                    }

                    else -> {
                        val olsonCode = callback.data.removePrefix("tz:")
                        try {
                            val zone = ZoneId.of(olsonCode)
                            if (!userService.updateTimezone(chatId.chatId.long, olsonCode)) {
                                sendTextMessage(chatId, msg.get("command.timezone.error.save", lang))
                                return@withTimeoutOrNull
                            }

                            val offset = zone.rules.getOffset(Instant.now(clock))
                            sendTextMessage(chatId, msg.get("command.timezone.saved", lang, olsonCode, offset))
                        } catch (e: DateTimeException) {
                            sendTextMessage(chatId, msg.get("command.timezone.error.unknown.retry", lang))
                        }
                    }
                }
            }

        if (completed == null) {
            sendTextMessage(chatId, msg.get("command.timezone.timeout", lang))
        }
    }

    companion object {
        private const val TIMEZONE_DIALOG_TIMEOUT_MS = 120_000L
    }
}
