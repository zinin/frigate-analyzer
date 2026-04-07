package ru.zinin.frigate.analyzer.telegram.bot.handler

import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
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

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class LanguageCommandHandler(
    private val userService: TelegramUserService,
    private val msg: MessageResolver,
) : CommandHandler {
    override val command: String = "language"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 6

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val chatId = message.chat.id
        val chatIdLong = chatId.chatId.long
        val lang = user?.languageCode ?: "ru"

        val completed =
            withTimeoutOrNull(LANGUAGE_DIALOG_TIMEOUT_MS) {
                val keyboard =
                    InlineKeyboardMarkup(
                        keyboard =
                            matrix {
                                row {
                                    +CallbackDataInlineKeyboardButton("🇷🇺 Русский", "lang:ru")
                                    +CallbackDataInlineKeyboardButton("🇬🇧 English", "lang:en")
                                }
                            },
                    )

                val sentMessage =
                    sendTextMessage(chatId, msg.get("command.language.prompt", lang), replyMarkup = keyboard)

                val callback =
                    waitDataCallbackQuery()
                        .filter {
                            it.data.startsWith("lang:") &&
                                (it as? MessageDataCallbackQuery)?.message?.let { m ->
                                    m.messageId == sentMessage.messageId && m.chat.id == chatId
                                } == true
                        }.first()
                answer(callback)

                val newLang = callback.data.removePrefix("lang:")
                if (!userService.updateLanguage(chatIdLong, newLang)) {
                    sendTextMessage(chatId, msg.get("common.error.generic", lang))
                    return@withTimeoutOrNull
                }

                val langName = if (newLang == "ru") "Русский" else "English"
                sendTextMessage(chatId, msg.get("command.language.set", newLang, langName))
            }

        if (completed == null) {
            sendTextMessage(chatId, msg.get("command.language.timeout", lang))
        }
    }

    companion object {
        private const val LANGUAGE_DIALOG_TIMEOUT_MS = 60_000L
    }
}
