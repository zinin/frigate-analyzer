package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
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
     * Оба пути используют [RetryHelper.retryBounded] — ограниченный ретрай по получателю с двумя
     * порогами, см. его KDoc, — КРОМЕ попытки по кэшированным `file_id`: она делается ровно один раз
     * и без ретрая, см. [sendRich]. Исчерпанный порог — исключение наружу: очередь пишет ERROR и
     * переходит к следующей задаче, уведомление этому получателю потеряно.
     * Note: If the calling coroutine is cancelled, this method will propagate
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
        RetryHelper.retryBounded("Send simple text", task.chatId) {
            bot.sendTextMessage(chatId = chatIdObj, text = task.text)
        }
    }

    private suspend fun sendRecording(task: RecordingNotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        val lang = task.language ?: "en"
        val exportKeyboard = quickExportHandler.createExportKeyboard(task.recordingId, lang)
        val frames = task.visualizedFrames.take(RichNotificationRenderer.MAX_MEDIA)
        if (frames.size < task.visualizedFrames.size) {
            // Сегодня недостижимо: LocalVisualizationProperties.maxFrames ограничен @Max(50), ровно
            // MAX_MEDIA. Но это равенство живёт в другом модуле и отправителю не видно, поэтому
            // молчаливая потеря кадров должна быть хотя бы находимой.
            logger.warn {
                "Dropping ${task.visualizedFrames.size - frames.size} frame(s) over the " +
                    "${RichNotificationRenderer.MAX_MEDIA} media cap (chat=${task.chatId}, recording=${task.recordingId})"
            }
        }

        // Описание существует только когда есть что описывать, включена сама фича и есть кому
        // править: без бина правки плейсхолдер остался бы висеть навсегда.
        val editRunner = editJobRunner.getIfAvailable()
        val describing = task.descriptionHandle != null && frames.isNotEmpty() && editRunner != null
        val state = if (describing) DescriptionState.Pending else DescriptionState.Absent
        val html = renderer.render(task.data, state, frames.size, lang)

        val sent = sendRich(chatIdObj, task, frames, html, exportKeyboard)
        val extracted =
            sent.content.richMessage.blocks
                .flatMap { it.photosInOrder() }
                // `PhotoFile.fileId` — это самый крупный размер, а не последний в лестнице `photo`:
                // её порядок Telegram нигде не обещает. Взяли бы последний — в кэш и в правку мог бы
                // уехать `file_id` превьюшки, и правка описания подменила бы уже доставленный
                // полноразмерный коллаж миниатюрами.
                .map { block -> block.photo.fileId }
        val editIds =
            if (extracted.size == frames.size) {
                task.frameIds.putIfAbsent(extracted)
                extracted
            } else {
                // Короткий список нельзя ни кэшировать (он навсегда разошёлся бы с числом кадров и
                // остальные получатели грузили бы байты), ни править по нему: правка переобъявляет
                // медиа целиком, и неполный массив стёр бы кадры из доставленного сообщения.
                // Но идентификаторы этой записи могут быть уже известны от получателя, чей ответ
                // пришёл целым: ПОЛНЫЙ массив переобъявляет ровно те же кадры, поэтому по нему
                // править безопасно — запрет касается неполного списка, а не чужого источника.
                logger.warn {
                    "Telegram returned ${extracted.size} photo ids for ${frames.size} frames " +
                        "(chat=${task.chatId}, recording=${task.recordingId}); not caching this answer"
                }
                task.frameIds.get()?.takeIf { it.size == frames.size }
            }
        // Порядок проверок значим. Сначала «описание вообще не запрашивалось»: у этого получателя
        // плейсхолдера в сообщении нет, и ручку надо отпустить независимо от того, разобрался ли
        // ответ Telegram. Стояла бы эта ветка ниже, испорченный ответ уводил бы выполнение в return
        // мимо отмены — и задача модели висела бы, хотя применять её результат некому.
        if (!describing) {
            // Плейсхолдера в сообщении нет, редактировать нечего — не жжём токены модели.
            task.descriptionHandle?.cancel()
            return
        }

        if (editIds == null) {
            // Ручку описания НЕ отменяем: она одна на всю запись, а испорченный ответ пришёл лично
            // этому получателю — отмена лишила бы описания и тех, чьи сообщения ушли нормально.
            logger.error {
                "No usable frame ids for chat=${task.chatId}, recording=${task.recordingId}: " +
                    "the placeholder in the delivered message will never be replaced"
            }
            return
        }
        val runner = requireNotNull(editRunner) { "describing == true implies the edit runner bean is present" }
        val handle = requireNotNull(task.descriptionHandle) { "describing == true implies descriptionHandle != null" }
        // Локальные копии, а не `task.recordingId` / `task.chatId` внутри лямбды: замыкание на задачу
        // удержало бы и `visualizedFrames` — байты всех кадров — на все десятки секунд ожидания
        // ответа модели.
        val recordingId = task.recordingId
        val chatId = task.chatId
        val target =
            EditTarget(
                chatId = chatIdObj,
                messageId = sent.messageId,
                data = task.data,
                fileIds = editIds,
                // Не запомненная клавиатура, а запрос актуальной в момент правки: к тому времени
                // пользователь мог запустить экспорт, и на сообщении висит «Отмена».
                keyboard = { quickExportHandler.currentKeyboard(recordingId, chatId, lang) },
                language = lang,
            )
        runner.launchEditJob(target) { handle.await() }
    }

    /**
     * Отправка с переиспользованием `file_id`, если их уже получил предыдущий получатель.
     *
     * Попытка по `file_id` делается ровно одна и БЕЗ бесконечного ретрая: иначе отказ
     * (устаревший или неприменимый идентификатор) крутился бы вечно и до загрузки байтов
     * дело бы не дошло. Загрузка байтами — уже с обычным `retryBounded`.
     *
     * Отказ и сбой различаются по тому, ответил ли Telegram. Любой ответ с ошибкой
     * ([RequestException], кроме флуд-контроля) сбрасывает общий кэш: библиотека узнаёт «wrong file
     * identifier» по буквальному тексту, а отказ по rich-медиа приходит с другим
     * (RICH_MESSAGE_PHOTO_INVALID), так что различать ответы по классу нельзя. Сбой без ответа —
     * сеть, таймаут, разбор — об идентификаторах не говорит ничего: он стоит этому получателю одной
     * загрузки, а идентификаторы записи остаются в силе для прочих.
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
            val media = RichNotificationRenderer.mediaFrom(cached)
            try {
                return deliver(chatIdObj, html, media, exportKeyboard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TooMuchRequestsException) {
                // Флуд-контроль об идентификаторах не говорит ничего. Штатно сюда не доходит —
                // лимитер ktgbotapi по умолчанию (ExceptionsOnlyLimiter) повторяет запрос сам, —
                // но если всплывёт, кэш трогать нельзя: иначе один 429 заставил бы каждого
                // оставшегося получателя грузить байты заново, ровно когда Telegram нас и душит.
                logger.warn(e) { "Cached-id send hit flood control for chat=${task.chatId}; falling back to upload, cache kept" }
            } catch (e: RequestException) {
                // Telegram ответил на отправку по кэшированным id ошибкой: идентификаторы под
                // подозрением. Сброс обязателен: putIfAbsent — это compareAndSet(null, ids), непустую
                // ячейку он не заменит, и без сброса запись навсегда осталась бы с негодными ids, а
                // каждый следующий получатель платил бы обречённую попытку плюс загрузку.
                logger.warn(e) { "Cached frame file_id rejected for chat=${task.chatId}; falling back to upload" }
                task.frameIds.invalidate()
            } catch (e: Exception) {
                // Ответа не было — сеть, таймаут, разбор ответа, — и об идентификаторах это не говорит
                // ничего, поэтому общий кэш остаётся. Этот получатель всё равно уходит на загрузку:
                // бесконечно ретраить те же идентификаторы нельзя — вечно негодный id остановил бы
                // единственного потребителя очереди.
                logger.warn(e) { "Cached-id send failed for chat=${task.chatId}; falling back to upload, cache kept" }
            }
        }
        val media =
            frames.mapIndexed { i, frame ->
                InputRichMessageMedia(
                    RichNotificationRenderer.mediaId(i),
                    TelegramMediaPhoto(frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg")),
                )
            }
        return RetryHelper.retryBounded("Send rich notification", task.chatId) {
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
     * и одиночное фото (рекурсия вырождается), и остальные контейнеры. Порядок обхода документный,
     * то есть тот же, в котором рендерер раздал `mediaId(0..n)`.
     *
     * **Граница рекурсии конечна.** [subBlocks] в 36.1.0 разбирает ровно пять типов —
     * `RichBlockList` (разворачивая `items` своих `RichBlockListItem`), `RichBlockBlockQuotation`,
     * `RichBlockCollage`, `RichBlockSlideshow`, `RichBlockDetails`, — а для всего прочего отдаёт
     * пустой список. `RichBlockListItem` собственным случаем НЕ является: встреченный на верхнем
     * уровне, он отдаст пустой список, а не свои блоки (проверено дизассемблером `getSubBlocks`).
     * `RichBlockTable` и `RichBlockFooter` в него НЕ входят. Сегодня рендерер фото туда не кладёт,
     * но если положит, обход их не увидит: `fileIds` недосчитается, сработает guard в [sendRecording],
     * и получатель навсегда останется с плейсхолдером. Новую обёртку с фото обязан сопровождать тест.
     */
    private fun RichBlock.photosInOrder(): List<RichBlockPhoto> =
        if (this is RichBlockPhoto) listOf(this) else subBlocks.flatMap { it.photosInOrder() }
}
