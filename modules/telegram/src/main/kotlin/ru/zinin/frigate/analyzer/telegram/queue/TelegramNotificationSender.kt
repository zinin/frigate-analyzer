package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.media.sendMediaGroup
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.requests.send.media.SendPhoto
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandler
import ru.zinin.frigate.analyzer.telegram.helper.RetryHelper
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramNotificationSender(
    private val bot: TelegramBot,
    private val quickExportHandler: QuickExportHandler,
    private val userService: TelegramUserService,
) {
    /**
     * Sends notification task to Telegram with infinite retry on failure.
     *
     * Note: If the calling coroutine is cancelled, this method will propagate
     * CancellationException and the task may not be delivered.
     */
    suspend fun send(task: NotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        val message = task.message.toCaption(MAX_CAPTION_LENGTH)
        val frames = task.visualizedFrames
        val lang = userService.getUserLanguage(task.chatId)
        val exportKeyboard = quickExportHandler.createExportKeyboard(task.recordingId, lang)

        when {
            frames.isEmpty() -> {
                RetryHelper.retryIndefinitely("Send text message", task.chatId) {
                    bot.sendTextMessage(
                        chatId = chatIdObj,
                        text = message,
                        replyMarkup = exportKeyboard,
                    )
                }
            }

            frames.size == 1 -> {
                val frame = frames.first()
                RetryHelper.retryIndefinitely("Send photo message", task.chatId) {
                    bot.execute(
                        SendPhoto(
                            chatId = chatIdObj,
                            photo = frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg"),
                            text = message,
                            replyMarkup = exportKeyboard,
                        ),
                    )
                }
            }

            else -> {
                frames.chunked(MAX_MEDIA_GROUP_SIZE).forEachIndexed { chunkIndex, chunk ->
                    RetryHelper.retryIndefinitely("Send media group", task.chatId) {
                        val mediaGroup =
                            chunk.mapIndexed { index, frame ->
                                TelegramMediaPhoto(
                                    file = frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg"),
                                    text = if (chunkIndex == 0 && index == 0) message else null,
                                )
                            }
                        // sendMediaGroup is @BetaApi in tgbotapi — stable enough for production use here
                        @Suppress("OPT_IN_USAGE")
                        bot.sendMediaGroup(chatIdObj, mediaGroup)
                    }
                }
                RetryHelper.retryIndefinitely("Send export button", task.chatId) {
                    bot.sendTextMessage(
                        chatId = chatIdObj,
                        text = EXPORT_PROMPT_TEXT,
                        replyMarkup = exportKeyboard,
                    )
                }
            }
        }
    }

    companion object {
        private const val MAX_MEDIA_GROUP_SIZE = 10
        private const val MAX_CAPTION_LENGTH = 1024
        private const val EXPORT_PROMPT_TEXT = "👆 Нажмите для быстрого экспорта видео"
    }

    private fun String.toCaption(maxLength: Int): String {
        if (length <= maxLength) {
            return this
        }
        logger.warn { "Truncating caption from $length to $maxLength characters to satisfy Telegram limits" }
        return substring(0, maxLength)
    }
}
