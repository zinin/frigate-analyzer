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
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
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
    private val descriptionPropertiesProvider: ObjectProvider<DescriptionProperties>,
) {
    /**
     * Sends notification task to Telegram with infinite retry on failure.
     *
     * When [NotificationTask.descriptionHandle] is non-null AND the description beans are
     * present, the initial message carries a placeholder rendered with HTML parse mode;
     * a background edit job (launched via [DescriptionEditJobRunner]) rewrites the caption
     * and details block once the AI call resolves. If the handle is null or the beans are
     * absent, the original plain-text single-send flow is preserved untouched.
     *
     * Note: If the calling coroutine is cancelled, this method will propagate
     * CancellationException and the task may not be delivered.
     */
    suspend fun send(task: NotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        val lang = task.language ?: "en"
        val exportKeyboard = quickExportHandler.createExportKeyboard(task.recordingId, lang)
        // formatter != null acts as the description-enabled gate downstream — helpers treat null
        // as "disabled path" and take the original plain-text branch without any extra flags.
        val formatter =
            if (task.descriptionHandle != null) descriptionFormatter.getIfAvailable() else null

        val parseMode: ParseMode? = if (formatter != null) HTMLParseMode else null
        val captionInitial =
            if (formatter != null) {
                formatter.captionInitialPlaceholder(task.message, lang, MAX_CAPTION_LENGTH)
            } else {
                task.message.toCaption(MAX_CAPTION_LENGTH)
            }

        // Edit-stage caption budget. In the HTML-placeholder flow reserve space for the worst-case
        // short description (bounded by DescriptionProperties.CommonSection.shortMaxLength, defined
        // in config with @Max(500)) plus the "\n\n" separator. When formatter is null the budget is
        // unused; MAX_CAPTION_LENGTH is just a sane non-zero default.
        val shortMaxLength = descriptionPropertiesProvider.getIfAvailable()?.common?.shortMaxLength ?: MAX_CAPTION_LENGTH
        val editBaseBudget =
            if (formatter != null) MAX_CAPTION_LENGTH - shortMaxLength - "\n\n".length else MAX_CAPTION_LENGTH

        val target: EditTarget? =
            when {
                task.visualizedFrames.isEmpty() -> {
                    sendEmptyText(chatIdObj, captionInitial, parseMode, exportKeyboard, task.chatId)
                    null
                }

                task.visualizedFrames.size == 1 -> {
                    sendSinglePhoto(
                        chatIdObj = chatIdObj,
                        frame = task.visualizedFrames.first(),
                        captionInitial = captionInitial,
                        parseMode = parseMode,
                        exportKeyboard = exportKeyboard,
                        formatter = formatter,
                        lang = lang,
                        task = task,
                        editBaseBudget = editBaseBudget,
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
                        editBaseBudget = editBaseBudget,
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
        task: NotificationTask,
        editBaseBudget: Int,
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
            captionBudget = editBaseBudget,
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
        task: NotificationTask,
        editBaseBudget: Int,
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
                                text =
                                    if (chunkIndex == 0 && idx == 0) {
                                        if (formatter != null) {
                                            formatter.captionInitialPlaceholder(task.message, lang, MAX_CAPTION_LENGTH)
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
            captionBudget = editBaseBudget,
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
