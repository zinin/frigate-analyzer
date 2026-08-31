package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendRichMessage
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.message.abstracts.ChatContentMessage
import dev.inmo.tgbotapi.types.message.content.RichMessageContent
import dev.inmo.tgbotapi.types.rich.InputRichMessageHTML
import dev.inmo.tgbotapi.types.rich.InputRichMessageMedia
import dev.inmo.tgbotapi.types.rich.RichBlock
import dev.inmo.tgbotapi.types.rich.RichBlockPhoto
import dev.inmo.tgbotapi.types.rich.subBlocks
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandler
import ru.zinin.frigate.analyzer.telegram.helper.RetryHelper
import ru.zinin.frigate.analyzer.telegram.service.impl.RichNotificationRenderer
import ru.zinin.frigate.analyzer.telegram.service.model.DescriptionState

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramNotificationSender(
    private val bot: TelegramBot,
    private val quickExportHandler: QuickExportHandler,
    private val renderer: RichNotificationRenderer,
    // ObjectProvider — бин правок существует только при application.ai.description.enabled=true.
    private val editJobRunner: ObjectProvider<DescriptionEditJobRunner>,
) {
    /**
     * Dispatches a notification to a single recipient. Two branches:
     * - [RecordingNotificationTask]: одно rich-сообщение с кадрами, таблицей метаданных и — если
     *   описание включено — плейсхолдерами, которые перепишет фоновая правка.
     * - [SimpleTextNotificationTask]: plain localized text message (used by signal-loss / recovery
     *   alerts). No video, no inline export buttons.
     *
     * Both branches use [RetryHelper.retryIndefinitely] for per-recipient infinite retry on
     * transient failures. Note: If the calling coroutine is cancelled, this method will propagate
     * CancellationException and the task may not be delivered.
     */
    suspend fun send(task: NotificationTask) {
        when (task) {
            is RecordingNotificationTask -> sendRecording(task)
            is SimpleTextNotificationTask -> sendSimpleText(task)
        }
    }

    private suspend fun sendSimpleText(task: SimpleTextNotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        RetryHelper.retryIndefinitely("Send simple text", task.chatId) {
            bot.sendTextMessage(chatId = chatIdObj, text = task.text)
        }
    }

    private suspend fun sendRecording(task: RecordingNotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        val lang = task.language ?: "en"
        val exportKeyboard = quickExportHandler.createExportKeyboard(task.recordingId, lang)
        val frames = task.visualizedFrames.take(RichNotificationRenderer.MAX_MEDIA)

        // Описание существует только когда есть что описывать, включена сама фича и есть кому
        // править: без бина правки плейсхолдер остался бы висеть навсегда.
        val editRunner = editJobRunner.getIfAvailable()
        val describing = task.descriptionHandle != null && frames.isNotEmpty() && editRunner != null
        val state = if (describing) DescriptionState.Pending else DescriptionState.Absent
        val html = renderer.render(task.data, state, frames.size, lang)

        val sent = sendRich(chatIdObj, task, frames, html, exportKeyboard)
        val fileIds =
            sent.content.richMessage.blocks
                .flatMap { it.photosInOrder() }
                .mapNotNull { it.photo.lastOrNull()?.fileId }
        if (fileIds.size != frames.size) {
            // Такой список нельзя ни кэшировать (он навсегда разошёлся бы с числом кадров и остальные
            // получатели грузили бы байты), ни править по нему: правка переобъявляет медиа целиком,
            // и неполный массив стёр бы кадры из уже доставленного сообщения. Ручку описания при этом
            // НЕ отменяем: она одна на всю запись, а испорченный ответ пришёл лично этому получателю —
            // отмена лишила бы описания и тех, чьи сообщения ушли нормально.
            logger.warn {
                "Telegram returned ${fileIds.size} photo ids for ${frames.size} frames (chat=${task.chatId}); " +
                    "skipping the file_id cache and the description edit for this recipient"
            }
            return
        }
        task.frameIds.putIfAbsent(fileIds)

        if (!describing) {
            // Плейсхолдера в сообщении нет, редактировать нечего — не жжём токены модели.
            task.descriptionHandle?.cancel()
            return
        }
        val runner = requireNotNull(editRunner) { "describing == true implies the edit runner bean is present" }
        val handle = requireNotNull(task.descriptionHandle) { "describing == true implies descriptionHandle != null" }
        val target =
            EditTarget(
                chatId = chatIdObj,
                messageId = sent.messageId,
                data = task.data,
                fileIds = fileIds,
                exportKeyboard = exportKeyboard,
                language = lang,
            )
        runner.launchEditJob(listOf(target)) { handle.await() }
    }

    /**
     * Отправка с переиспользованием `file_id`, если их уже получил предыдущий получатель.
     *
     * Попытка по `file_id` делается ровно одна и БЕЗ бесконечного ретрая: иначе отказ
     * (устаревший или неприменимый идентификатор) крутился бы вечно и до загрузки байтов
     * дело бы не дошло. Загрузка байтами — уже с обычным `retryIndefinitely`.
     */
    private suspend fun sendRich(
        chatIdObj: ChatId,
        task: RecordingNotificationTask,
        frames: List<VisualizedFrameData>,
        html: String,
        exportKeyboard: InlineKeyboardMarkup,
    ): ChatContentMessage<RichMessageContent> {
        val cached = task.frameIds.get()
        if (cached != null && cached.size == frames.size && frames.isNotEmpty()) {
            val media =
                cached.mapIndexed { i, id -> InputRichMessageMedia(RichNotificationRenderer.mediaId(i), TelegramMediaPhoto(id)) }
            try {
                return deliver(chatIdObj, html, media, exportKeyboard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Cached frame file_id rejected for chat=${task.chatId}; falling back to upload" }
                task.frameIds.invalidate()
            }
        }
        val media =
            frames.mapIndexed { i, frame ->
                InputRichMessageMedia(
                    RichNotificationRenderer.mediaId(i),
                    TelegramMediaPhoto(frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg")),
                )
            }
        return RetryHelper.retryIndefinitely("Send rich notification", task.chatId) {
            deliver(chatIdObj, html, media, exportKeyboard)
        }
    }

    private suspend fun deliver(
        chatIdObj: ChatId,
        html: String,
        media: List<InputRichMessageMedia>,
        exportKeyboard: InlineKeyboardMarkup,
    ): ChatContentMessage<RichMessageContent> =
        bot.sendRichMessage(
            chatIdObj,
            InputRichMessageHTML(html, media = media.ifEmpty { null }),
            replyMarkup = exportKeyboard,
        )

    /**
     * Фото в порядке документа: и `<img>` верхнего уровня, и вложенные — рендерер заворачивает
     * несколько кадров в `<tg-collage>`, а он приходит контейнером [dev.inmo.tgbotapi.types.rich.RichBlockCollage],
     * чьи фото лежат уровнем ниже. Обход рекурсивный, а не спецкейс на коллаж: [subBlocks] закрывает
     * и одиночное фото (рекурсия вырождается), и любую будущую обёртку. Порядок обхода документный,
     * то есть тот же, в котором рендерер раздал `mediaId(0..n)`.
     */
    private fun RichBlock.photosInOrder(): List<RichBlockPhoto> =
        if (this is RichBlockPhoto) listOf(this) else subBlocks.flatMap { it.photosInOrder() }
}
