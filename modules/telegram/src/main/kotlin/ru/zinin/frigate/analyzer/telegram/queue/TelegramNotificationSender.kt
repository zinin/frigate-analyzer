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
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
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
     * When [NotificationTask.descriptionHandle] is non-null AND the description beans are
     * present, the initial message carries a placeholder rendered with HTML parse mode;
     * a background edit job (launched via [DescriptionEditJobRunner]) rewrites the caption
     * and details block once the AI call resolves. If the handle is null or the beans are
     * absent, the original plain-text single-send flow is preserved untouched.
     *
     * Note: If the calling coroutine is cancelled, this method will propagate
     * CancellationException and the task may not be delivered.
     */
    @Suppress("LongMethod")
    suspend fun send(task: NotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        val frames = task.visualizedFrames
        val lang = task.language ?: "en"
        val exportKeyboard = quickExportHandler.createExportKeyboard(task.recordingId, lang)
        val formatter = descriptionFormatter.getIfAvailable()
        val withDescription = task.descriptionHandle != null && formatter != null

        // When description is active, the initial caption is `{message}\n\n{placeholder.short}`
        // rendered as HTML. Without description the original plain-text path is preserved.
        val parseMode = if (withDescription) HTMLParseMode else null
        val captionInitial =
            if (withDescription) {
                // Smart-cast: `withDescription` implies `formatter != null` via the conjunction above.
                formatter.captionInitialPlaceholder(task.message, lang, MAX_CAPTION_LENGTH)
            } else {
                task.message.toCaption(MAX_CAPTION_LENGTH)
            }

        // Caption budget for the EDIT stage: in the HTML-placeholder flow we reserve space for
        // the worst-case short description (bounded by DescriptionProperties.CommonSection @Max)
        // plus the `\n\n` separator. Without description we use the full 1024 budget.
        val editBaseBudget =
            if (withDescription) MAX_CAPTION_LENGTH - SHORT_MAX_LENGTH - "\n\n".length else MAX_CAPTION_LENGTH

        val targets = mutableListOf<EditTarget>()

        when {
            frames.isEmpty() -> {
                RetryHelper.retryIndefinitely("Send text message", task.chatId) {
                    bot.sendTextMessage(
                        chatId = chatIdObj,
                        text = captionInitial,
                        parseMode = parseMode,
                        replyMarkup = exportKeyboard,
                    )
                }
            }

            frames.size == 1 -> {
                val frame = frames.first()
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
                if (withDescription) {
                    val detailsMsg =
                        RetryHelper.retryIndefinitely("Send details placeholder", task.chatId) {
                            bot.sendTextMessage(
                                chatId = chatIdObj,
                                text = formatter.placeholderDetailedExpandable(lang),
                                parseMode = HTMLParseMode,
                                replyParameters = ReplyParameters(chatIdObj, photoMsg.messageId),
                            )
                        }
                    targets.add(
                        EditTarget(
                            chatId = chatIdObj,
                            captionMessageId = photoMsg.messageId,
                            detailsMessageId = detailsMsg.messageId,
                            baseText = task.message,
                            captionBudget = editBaseBudget,
                            exportKeyboard = exportKeyboard,
                            language = lang,
                            isMediaGroup = false,
                        ),
                    )
                }
            }

            else -> {
                // Only capture the first-album message ID when description is enabled — we only
                // need it to turn the export-button text message into a reply to the album, which
                // anchors the AI-description details block under the album. When description is
                // disabled, the pre-Task-16 behaviour is a standalone export-button message (no
                // replyParameters); preserve that byte-for-byte.
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
                                                if (withDescription) {
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
                    if (withDescription && chunkIndex == 0) {
                        firstAlbumMessageId = group.messageId
                    }
                }
                val albumBaseText = msg.get("notification.recording.export.prompt", lang)
                val promptInitial =
                    if (withDescription) {
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
                            parseMode = if (withDescription) HTMLParseMode else null,
                            replyParameters = firstAlbumMessageId?.let { ReplyParameters(chatIdObj, it) },
                            replyMarkup = exportKeyboard,
                        )
                    }
                if (withDescription) {
                    targets.add(
                        EditTarget(
                            chatId = chatIdObj,
                            captionMessageId = null,
                            detailsMessageId = detailsMsg.messageId,
                            baseText = albumBaseText,
                            captionBudget = editBaseBudget,
                            exportKeyboard = exportKeyboard,
                            language = lang,
                            isMediaGroup = true,
                        ),
                    )
                }
            }
        }

        if (withDescription && targets.isNotEmpty()) {
            val runner = editJobRunner.getIfAvailable()
            if (runner != null) {
                // task.descriptionHandle is non-null here (withDescription guards it).
                val handle = task.descriptionHandle
                if (handle != null) {
                    runner.launchEditJob(targets) { handle.await() }
                }
            }
        }
    }

    companion object {
        private const val MAX_MEDIA_GROUP_SIZE = 10
        private const val MAX_CAPTION_LENGTH = 1024

        // Pessimistic worst-case short-description length, matching @Max(500) on
        // DescriptionProperties.CommonSection.maxShortCharacters. Used as the reserved
        // slice of the caption budget so the final edit never overflows Telegram's 1024 limit.
        private const val SHORT_MAX_LENGTH = 500
    }

    private fun String.toCaption(maxLength: Int): String {
        if (length <= maxLength) {
            return this
        }
        logger.warn { "Truncating caption from $length to $maxLength characters to satisfy Telegram limits" }
        return substring(0, maxLength)
    }
}
