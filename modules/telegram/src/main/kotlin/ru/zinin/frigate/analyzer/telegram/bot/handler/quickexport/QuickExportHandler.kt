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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.StartCommandHandler
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
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
    private val msg: MessageResolver,
    private val userService: TelegramUserService,
    private val exportScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private val activeExports: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @Suppress("LongMethod")
    suspend fun handle(callback: DataCallbackQuery): Job? {
        val messageCallback = callback as? MessageDataCallbackQuery
        val message = messageCallback?.message
        if (message == null) {
            bot.answer(callback)
            return null
        }
        val chatId = message.chat.id

        val callbackData = callback.data
        val mode =
            if (callbackData.startsWith(CALLBACK_PREFIX_ANNOTATED)) ExportMode.ANNOTATED else ExportMode.ORIGINAL

        // Parse recordingId from callback data
        val recordingId = parseRecordingId(callbackData)
        if (recordingId == null) {
            logger.warn { "Invalid recordingId in callback: $callbackData" }
            val lang =
                try {
                    userService.getUserLanguage(chatId.chatId.long)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    StartCommandHandler.detectLanguage(callback.user.ietfLanguageCode?.code)
                }
            bot.answer(callback, msg.get("quickexport.error.format", lang))
            return null
        }

        // Check username presence
        val user = callback.user
        val username = user.username?.withoutAt
        if (username == null) {
            val lang = StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
            bot.answer(callback, msg.get("quickexport.error.username", lang))
            return null
        }

        // Check authorization
        if (authorizationFilter.getRole(username) == null) {
            val lang = StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
            bot.answer(callback, msg.get("common.error.unauthorized", lang))
            return null
        }

        // Resolve language
        val lang =
            try {
                userService.getUserLanguage(chatId.chatId.long)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
            }

        // Prevent duplicate exports
        if (!activeExports.add(recordingId)) {
            bot.answer(callback, msg.get("quickexport.error.concurrent", lang))
            return null
        }

        // Answer callback immediately
        bot.answer(callback)

        // Switch button to processing state
        try {
            bot.editMessageReplyMarkup(
                message,
                replyMarkup = createProcessingKeyboard(recordingId, msg.get("quickexport.button.processing", lang), mode),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to update button to processing state" }
        }

        // Launch export in background so handle() returns immediately,
        // allowing subsequent callbacks to be processed (and blocked by activeExports)
        return exportScope.launch {
            try {
                val timeout =
                    if (mode == ExportMode.ANNOTATED) {
                        QUICK_EXPORT_ANNOTATED_TIMEOUT_MS
                    } else {
                        QUICK_EXPORT_ORIGINAL_TIMEOUT_MS
                    }

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
                        val text = renderProgressButton(progress.stage, progress.percent, lang)
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
                    bot.sendTextMessage(chatId, msg.get("quickexport.error.timeout", lang))
                    restoreButton(message, recordingId, lang)
                    return@launch
                }

                try {
                    try {
                        bot.editMessageReplyMarkup(
                            message,
                            replyMarkup =
                                createProcessingKeyboard(
                                    recordingId,
                                    msg.get("quickexport.progress.sending", lang),
                                    mode,
                                ),
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
                        bot.sendTextMessage(chatId, msg.get("quickexport.error.send.timeout", lang))
                    }
                } finally {
                    try {
                        videoExportService.cleanupExportFile(videoPath)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to cleanup: $videoPath" }
                    }
                }

                // Restore buttons
                restoreButton(message, recordingId, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Quick export failed for recording $recordingId" }
                val errorMsg =
                    when (e) {
                        is IllegalArgumentException -> msg.get("quickexport.error.not.found", lang)
                        is IllegalStateException -> msg.get("quickexport.error.unavailable", lang)
                        else -> msg.get("quickexport.error.generic", lang)
                    }
                bot.sendTextMessage(chatId, errorMsg)
                restoreButton(message, recordingId, lang)
            } finally {
                activeExports.remove(recordingId)
            }
        }
    }

    private fun renderProgressButton(
        stage: VideoExportProgress.Stage,
        percent: Int? = null,
        lang: String,
    ): String =
        when (stage) {
            VideoExportProgress.Stage.PREPARING -> {
                msg.get("quickexport.progress.preparing", lang)
            }

            VideoExportProgress.Stage.MERGING -> {
                msg.get("quickexport.progress.merging", lang)
            }

            VideoExportProgress.Stage.COMPRESSING -> {
                msg.get("quickexport.progress.compressing", lang)
            }

            VideoExportProgress.Stage.ANNOTATING -> {
                if (percent != null) {
                    msg.get("quickexport.progress.annotating.percent", lang, percent)
                } else {
                    msg.get("quickexport.progress.annotating", lang)
                }
            }

            VideoExportProgress.Stage.SENDING -> {
                msg.get("quickexport.progress.sending", lang)
            }

            VideoExportProgress.Stage.DONE -> {
                msg.get("quickexport.progress.done", lang)
            }
        }

    fun createExportKeyboard(
        recordingId: UUID,
        lang: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            keyboard =
                matrix {
                    row {
                        +CallbackDataInlineKeyboardButton(
                            msg.get("quickexport.button.original", lang),
                            "$CALLBACK_PREFIX$recordingId",
                        )
                        +CallbackDataInlineKeyboardButton(
                            msg.get("quickexport.button.annotated", lang),
                            "$CALLBACK_PREFIX_ANNOTATED$recordingId",
                        )
                    }
                },
        )

    private suspend fun restoreButton(
        message: ContentMessage<*>?,
        recordingId: UUID,
        lang: String,
    ) {
        if (message == null) return
        try {
            bot.editMessageReplyMarkup(
                message,
                replyMarkup = createExportKeyboard(recordingId, lang),
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

        fun createProcessingKeyboard(
            recordingId: UUID,
            text: String = "\u2699\uFE0F Exporting...",
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
    }
}
