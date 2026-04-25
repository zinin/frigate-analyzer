package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.media.sendMediaGroup
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.requests.send.media.SendPhoto
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import dev.inmo.tgbotapi.types.message.ParseMode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandler
import ru.zinin.frigate.analyzer.telegram.helper.RetryHelper
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramNotificationSender(
    private val bot: TelegramBot,
    private val quickExportHandler: QuickExportHandler,
    private val msg: MessageResolver,
    // ObjectProvider — the description beans are absent when application.ai.description.enabled=false.
    private val descriptionFormatter: ObjectProvider<DescriptionMessageFormatter>,
    private val editJobRunner: ObjectProvider<DescriptionEditJobRunner>,
) {
    /**
     * Sends notification task to Telegram with infinite retry on failure.
     *
     * When [RecordingNotificationTask.descriptionHandle] is non-null AND the description beans are
     * present, the initial message carries a placeholder rendered with HTML parse mode;
     * a background edit job (launched via [DescriptionEditJobRunner]) rewrites the caption
     * and details block once the AI call resolves. If the handle is null or the beans are
     * absent, the original plain-text single-send flow is preserved untouched.
     *
     * Note: If the calling coroutine is cancelled, this method will propagate
     * CancellationException and the task may not be delivered.
     */
    suspend fun send(task: NotificationTask) {
        when (task) {
            is RecordingNotificationTask -> {
                sendRecording(task)
            }

            is SimpleTextNotificationTask -> {
                sendSimpleText(task)
            }
        }
    }

    private suspend fun sendSimpleText(task: SimpleTextNotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        RetryHelper.retryIndefinitely("Send simple text", task.chatId) {
            bot.sendTextMessage(
                chatId = chatIdObj,
                text = task.text,
            )
        }
    }

    private suspend fun sendRecording(task: RecordingNotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        val lang = task.language ?: "en"
        val exportKeyboard = quickExportHandler.createExportKeyboard(task.recordingId, lang)
        // formatter != null acts as the description-enabled gate downstream — helpers treat null
        // as "disabled path" and take the original plain-text branch without any extra flags.
        val formatter =
            if (task.descriptionHandle != null) descriptionFormatter.getIfAvailable() else null

        val parseMode: ParseMode? = if (formatter != null) HTMLParseMode else null

        val target: EditTarget? =
            when {
                task.visualizedFrames.isEmpty() -> {
                    // No photo → no editable target. Send baseText WITHOUT the AI placeholder
                    // (a stuck "⏳" would never get replaced) and cancel the in-flight describe job
                    // so tokens are not wasted.
                    val emptyCaption =
                        if (formatter != null) {
                            formatter.captionBasePlain(task.message, MAX_CAPTION_LENGTH)
                        } else {
                            task.message.toCaption(MAX_CAPTION_LENGTH)
                        }
                    sendEmptyText(chatIdObj, emptyCaption, parseMode, exportKeyboard, task.chatId)
                    if (formatter != null) task.descriptionHandle?.cancel()
                    null
                }

                task.visualizedFrames.size == 1 -> {
                    val singleCaptionInitial =
                        if (formatter != null) {
                            formatter.captionInitialPlaceholder(task.message, lang, MAX_CAPTION_LENGTH)
                        } else {
                            task.message.toCaption(MAX_CAPTION_LENGTH)
                        }
                    sendSinglePhoto(
                        chatIdObj = chatIdObj,
                        frame = task.visualizedFrames.first(),
                        captionInitial = singleCaptionInitial,
                        parseMode = parseMode,
                        exportKeyboard = exportKeyboard,
                        formatter = formatter,
                        lang = lang,
                        task = task,
                    )
                }

                else -> {
                    sendMediaGroupMessages(
                        chatIdObj = chatIdObj,
                        frames = task.visualizedFrames,
                        parseMode = parseMode,
                        exportKeyboard = exportKeyboard,
                        formatter = formatter,
                        lang = lang,
                        task = task,
                    )
                }
            }

        if (formatter != null && target != null) {
            val runner = editJobRunner.getIfAvailable() ?: return
            val handle =
                requireNotNull(task.descriptionHandle) {
                    "formatter != null implies descriptionHandle != null (gated on task.descriptionHandle at line ~57)"
                }
            runner.launchEditJob(listOf(target)) { handle.await() }
        }
    }

    private suspend fun sendEmptyText(
        chatIdObj: ChatId,
        captionInitial: String,
        parseMode: ParseMode?,
        exportKeyboard: InlineKeyboardMarkup,
        chatId: Long,
    ) {
        RetryHelper.retryIndefinitely("Send text message", chatId) {
            bot.sendTextMessage(
                chatId = chatIdObj,
                text = captionInitial,
                parseMode = parseMode,
                replyMarkup = exportKeyboard,
            )
        }
    }

    @Suppress("LongParameterList")
    private suspend fun sendSinglePhoto(
        chatIdObj: ChatId,
        frame: VisualizedFrameData,
        captionInitial: String,
        parseMode: ParseMode?,
        exportKeyboard: InlineKeyboardMarkup,
        formatter: DescriptionMessageFormatter?,
        lang: String,
        task: RecordingNotificationTask,
    ): EditTarget? {
        val photoMsg =
            RetryHelper.retryIndefinitely("Send photo message", task.chatId) {
                bot.execute(
                    SendPhoto(
                        chatId = chatIdObj,
                        photo = frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg"),
                        text = captionInitial,
                        parseMode = parseMode,
                        replyMarkup = exportKeyboard,
                    ),
                )
            }
        if (formatter == null) return null
        val detailsMsg =
            RetryHelper.retryIndefinitely("Send details placeholder", task.chatId) {
                bot.sendTextMessage(
                    chatId = chatIdObj,
                    text = formatter.placeholderDetailedExpandable(lang),
                    parseMode = HTMLParseMode,
                    replyParameters = ReplyParameters(chatIdObj, photoMsg.messageId),
                )
            }
        return EditTarget(
            chatId = chatIdObj,
            captionMessageId = photoMsg.messageId,
            detailsMessageId = detailsMsg.messageId,
            baseText = task.message,
            exportKeyboard = exportKeyboard,
            language = lang,
            isMediaGroup = false,
        )
    }

    @Suppress("LongParameterList", "LongMethod")
    private suspend fun sendMediaGroupMessages(
        chatIdObj: ChatId,
        frames: List<VisualizedFrameData>,
        parseMode: ParseMode?,
        exportKeyboard: InlineKeyboardMarkup,
        formatter: DescriptionMessageFormatter?,
        lang: String,
        task: RecordingNotificationTask,
    ): EditTarget? {
        // Only capture the first-album id when description is enabled — the disabled path must
        // not turn the export button into a reply (preserves pre-Task-16 behaviour).
        var firstAlbumMessageId: MessageId? = null
        frames.chunked(MAX_MEDIA_GROUP_SIZE).forEachIndexed { chunkIndex, chunk ->
            val group =
                RetryHelper.retryIndefinitely("Send media group", task.chatId) {
                    val media =
                        chunk.mapIndexed { idx, frame ->
                            TelegramMediaPhoto(
                                file = frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg"),
                                // Media-group photos have no editable caption target (the edit flow
                                // only rewrites the reply-text message). An AI placeholder here would
                                // stay stuck forever, so the first photo carries plain baseText and
                                // the placeholder lives in the reply text below.
                                text =
                                    if (chunkIndex == 0 && idx == 0) {
                                        if (formatter != null) {
                                            formatter.captionBasePlain(task.message, MAX_CAPTION_LENGTH)
                                        } else {
                                            task.message.toCaption(MAX_CAPTION_LENGTH)
                                        }
                                    } else {
                                        null
                                    },
                                parseMode = if (chunkIndex == 0 && idx == 0) parseMode else null,
                            )
                        }
                    // sendMediaGroup is @BetaApi in tgbotapi — stable enough for production use here
                    @Suppress("OPT_IN_USAGE")
                    bot.sendMediaGroup(chatIdObj, media)
                }
            if (formatter != null && chunkIndex == 0) {
                firstAlbumMessageId = group.messageId
            }
        }
        val albumBaseText = msg.get("notification.recording.export.prompt", lang)
        val promptInitial =
            if (formatter != null) {
                albumBaseText +
                    "\n\n" +
                    formatter.placeholderShort(lang) +
                    "\n\n" +
                    formatter.placeholderDetailedExpandable(lang)
            } else {
                albumBaseText
            }
        val detailsMsg =
            RetryHelper.retryIndefinitely("Send export button", task.chatId) {
                bot.sendTextMessage(
                    chatId = chatIdObj,
                    text = promptInitial,
                    parseMode = if (formatter != null) HTMLParseMode else null,
                    replyParameters = firstAlbumMessageId?.let { ReplyParameters(chatIdObj, it) },
                    replyMarkup = exportKeyboard,
                )
            }
        if (formatter == null) return null
        return EditTarget(
            chatId = chatIdObj,
            captionMessageId = null,
            detailsMessageId = detailsMsg.messageId,
            baseText = albumBaseText,
            exportKeyboard = exportKeyboard,
            language = lang,
            isMediaGroup = true,
        )
    }

    companion object {
        private const val MAX_MEDIA_GROUP_SIZE = 10
        private const val MAX_CAPTION_LENGTH = 1024
    }

    private fun String.toCaption(maxLength: Int): String {
        if (length <= maxLength) {
            return this
        }
        logger.warn { "Truncating caption from $length to $maxLength characters to satisfy Telegram limits" }
        return substring(0, maxLength)
    }
}
