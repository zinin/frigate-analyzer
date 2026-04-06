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
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class QuickExportHandler(
    private val bot: TelegramBot,
    private val videoExportService: VideoExportService,
    private val authorizationFilter: AuthorizationFilter,
    private val properties: TelegramProperties,
) {
    private val activeExports: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    suspend fun handle(callback: DataCallbackQuery) {
        val messageCallback = callback as? MessageDataCallbackQuery
        val message = messageCallback?.message
        if (message == null) {
            bot.answer(callback)
            return
        }
        val chatId = message.chat.id

        val callbackData = callback.data
        val mode =
            if (callbackData.startsWith(CALLBACK_PREFIX_ANNOTATED)) ExportMode.ANNOTATED else ExportMode.ORIGINAL

        // Parse recordingId from callback data
        val recordingId = parseRecordingId(callbackData)
        if (recordingId == null) {
            logger.warn { "Invalid recordingId in callback: $callbackData" }
            bot.answer(callback, "Ошибка: неверный формат данных")
            return
        }

        // Check username presence
        val user = callback.user
        val username = user.username?.withoutAt
        if (username == null) {
            bot.answer(callback, "Пожалуйста, установите username в настройках Telegram.")
            return
        }

        // Check authorization
        if (authorizationFilter.getRole(username) == null) {
            bot.answer(callback, properties.unauthorizedMessage)
            return
        }

        // Prevent duplicate exports
        if (!activeExports.add(recordingId)) {
            bot.answer(callback, "Экспорт уже выполняется.")
            return
        }

        // Answer callback immediately
        bot.answer(callback)

        // Switch button to processing state
        try {
            bot.editMessageReplyMarkup(
                message,
                replyMarkup = createProcessingKeyboard(recordingId, mode = mode),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to update button to processing state" }
        }

        try {
            val timeout =
                if (mode == ExportMode.ANNOTATED) QUICK_EXPORT_ANNOTATED_TIMEOUT_MS else QUICK_EXPORT_ORIGINAL_TIMEOUT_MS

            var lastRenderedStage: VideoExportProgress.Stage? = null
            var lastRenderedPercent: Int? = null

            val onProgress: suspend (VideoExportProgress) -> Unit = { progress ->
                val shouldUpdate =
                    when {
                        progress.stage != lastRenderedStage -> {
                            true
                        }

                        progress.stage == VideoExportProgress.Stage.ANNOTATING && progress.percent != null -> {
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
                    val text = renderProgressButton(progress.stage, progress.percent)
                    try {
                        bot.editMessageReplyMarkup(
                            message,
                            replyMarkup = createProcessingKeyboard(recordingId, text, mode),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to update progress button" }
                    }
                }
            }

            val videoPath =
                withTimeoutOrNull(timeout) {
                    videoExportService.exportByRecordingId(recordingId, mode = mode, onProgress = onProgress)
                }

            if (videoPath == null) {
                bot.sendTextMessage(chatId, "Экспорт занял слишком много времени. Попробуйте позже.")
                restoreButton(message, recordingId)
                return
            }

            try {
                try {
                    bot.editMessageReplyMarkup(
                        message,
                        replyMarkup = createProcessingKeyboard(recordingId, "⚙️ Отправка...", mode),
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update progress button to sending" }
                }

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

            // Restore buttons
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
        } finally {
            activeExports.remove(recordingId)
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
        const val CALLBACK_PREFIX_ANNOTATED = "qea:"
        private const val QUICK_EXPORT_ORIGINAL_TIMEOUT_MS = 300_000L // 5 minutes
        private const val QUICK_EXPORT_ANNOTATED_TIMEOUT_MS = 1_200_000L // 20 minutes

        internal fun parseRecordingId(callbackData: String): UUID? {
            val recordingIdStr =
                callbackData
                    .removePrefix(CALLBACK_PREFIX_ANNOTATED)
                    .removePrefix(CALLBACK_PREFIX)
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
                            +CallbackDataInlineKeyboardButton("📹 Оригинал", "$CALLBACK_PREFIX$recordingId")
                            +CallbackDataInlineKeyboardButton("📹 С объектами", "$CALLBACK_PREFIX_ANNOTATED$recordingId")
                        }
                    },
            )

        fun createProcessingKeyboard(
            recordingId: UUID,
            text: String = "⚙️ Экспорт...",
            mode: ExportMode = ExportMode.ORIGINAL,
        ): InlineKeyboardMarkup {
            val prefix = if (mode == ExportMode.ANNOTATED) CALLBACK_PREFIX_ANNOTATED else CALLBACK_PREFIX
            return InlineKeyboardMarkup(
                keyboard =
                    matrix {
                        row {
                            +CallbackDataInlineKeyboardButton(text, "$prefix$recordingId")
                        }
                    },
            )
        }

        private fun renderProgressButton(
            stage: VideoExportProgress.Stage,
            percent: Int? = null,
        ): String =
            when (stage) {
                VideoExportProgress.Stage.PREPARING -> {
                    "⚙️ Подготовка..."
                }

                VideoExportProgress.Stage.MERGING -> {
                    "⚙️ Склейка видео..."
                }

                VideoExportProgress.Stage.COMPRESSING -> {
                    "⚙️ Сжатие видео..."
                }

                VideoExportProgress.Stage.ANNOTATING -> {
                    if (percent != null) "⚙️ Аннотация $percent%..." else "⚙️ Аннотация..."
                }

                VideoExportProgress.Stage.SENDING -> {
                    "⚙️ Отправка..."
                }

                VideoExportProgress.Stage.DONE -> {
                    "✅ Готово"
                }
            }
    }
}
