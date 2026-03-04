package ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.media.sendVideo
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class QuickExportHandler(
    private val bot: TelegramBot,
    private val videoExportService: VideoExportService,
    private val authorizationFilter: AuthorizationFilter,
    private val properties: TelegramProperties,
) {
    suspend fun handle(callback: DataCallbackQuery) {
        val messageCallback = callback as? MessageDataCallbackQuery
        val message = messageCallback?.message
        if (message == null) {
            bot.answer(callback)
            return
        }
        val chatId = message.chat.id

        // Парсим recordingId из callback data
        val recordingId = parseRecordingId(callback.data)
        if (recordingId == null) {
            logger.warn { "Invalid recordingId in callback: ${callback.data}" }
            bot.answer(callback, "Ошибка: неверный формат данных")
            return
        }

        // Проверяем наличие username
        val user = callback.user
        val username = user.username?.withoutAt
        if (username == null) {
            bot.answer(callback, "Пожалуйста, установите username в настройках Telegram.")
            return
        }

        // Проверяем авторизацию через общий фильтр
        if (authorizationFilter.getRole(username) == null) {
            bot.answer(callback, properties.unauthorizedMessage)
            return
        }

        // Answer callback сразу (чтобы убрать индикатор загрузки)
        bot.answer(callback)

        // Меняем кнопку на "Экспорт..."
        try {
            bot.editMessageReplyMarkup(
                message,
                replyMarkup = createProcessingKeyboard(recordingId),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to update button to processing state" }
        }

        try {
            val videoPath =
                withTimeoutOrNull(QUICK_EXPORT_TIMEOUT_MS) {
                    videoExportService.exportByRecordingId(recordingId)
                }

            if (videoPath == null) {
                bot.sendTextMessage(chatId, "Экспорт занял слишком много времени. Попробуйте позже.")
                restoreButton(message, recordingId)
                return
            }

            try {
                val sent =
                    withTimeoutOrNull(properties.sendVideoTimeout.toMillis()) {
                        bot.sendVideo(
                            chatId,
                            videoPath.toFile().asMultipartFile().copy(filename = "quick_export_$recordingId.mp4"),
                        )
                    }

                if (sent == null) {
                    bot.sendTextMessage(chatId, "Не удалось отправить видео: превышено время ожидания.")
                }
            } finally {
                try {
                    videoExportService.cleanupExportFile(videoPath)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to cleanup: $videoPath" }
                }
            }

            // Восстанавливаем кнопку
            restoreButton(message, recordingId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Quick export failed for recording $recordingId" }
            val errorMsg =
                when (e) {
                    is IllegalArgumentException -> "Запись не найдена."
                    is IllegalStateException -> "Файлы записи недоступны."
                    else -> "Ошибка экспорта. Попробуйте позже."
                }
            bot.sendTextMessage(chatId, errorMsg)
            restoreButton(message, recordingId)
        }
    }

    private suspend fun restoreButton(
        message: ContentMessage<*>?,
        recordingId: UUID,
    ) {
        if (message == null) return
        try {
            bot.editMessageReplyMarkup(
                message,
                replyMarkup = createExportKeyboard(recordingId),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to restore button" }
        }
    }

    companion object {
        const val CALLBACK_PREFIX = "qe:"
        private const val QUICK_EXPORT_TIMEOUT_MS = 300_000L // 5 минут

        internal fun parseRecordingId(callbackData: String): UUID? {
            val recordingIdStr = callbackData.removePrefix(CALLBACK_PREFIX)
            return try {
                UUID.fromString(recordingIdStr)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun createExportKeyboard(recordingId: UUID): InlineKeyboardMarkup =
            InlineKeyboardMarkup(
                keyboard =
                    matrix {
                        row {
                            +CallbackDataInlineKeyboardButton("📹 Экспорт видео", "$CALLBACK_PREFIX$recordingId")
                        }
                    },
            )

        fun createProcessingKeyboard(recordingId: UUID): InlineKeyboardMarkup =
            InlineKeyboardMarkup(
                keyboard =
                    matrix {
                        row {
                            +CallbackDataInlineKeyboardButton("⚙️ Экспорт...", "$CALLBACK_PREFIX$recordingId")
                        }
                    },
            )
    }
}
