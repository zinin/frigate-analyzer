package ru.zinin.frigate.analyzer.telegram.service.impl

import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.rich.InputRichMessageMedia
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.model.DescriptionState
import ru.zinin.frigate.analyzer.telegram.service.model.RecordingNotificationData

/**
 * Собирает HTML rich-сообщения уведомления. Единственное место, где верстается уведомление:
 * и первичная отправка, и правка после ответа модели зовут один и тот же [render].
 *
 * Безусловный компонент — сообщение строится всегда, а AI-описание лишь одно из его состояний.
 *
 * **Граница доверия.** Экранируется всё, кроме трёх строк бандла, которые обязаны быть разметкой:
 * `ai.description.placeholder.short`, `…placeholder.detailed` и `ai.description.fallback.unavailable`
 * — это `⏳ <i>…</i>`, и экранирование показало бы литеральные `&lt;i&gt;`. Заголовок, подписи ячеек
 * и заголовок раскрывашки разметкой не являются никогда, поэтому идут через [msgText] и правка
 * `.properties` (`R&D camera`, `Кадров < 10`) не может отправить в Telegram битый HTML: отказ был бы
 * детерминированным, а первичная отправка ретраится бесконечно и держит единственного потребителя
 * очереди. Поля [RecordingNotificationData] и оба текста модели — чужой ввод, экранируются всегда.
 */
@Component
class RichNotificationRenderer(
    private val msg: MessageResolver,
) {
    /**
     * **Контракт с вызывающим.** [frameCount] молча клампится до [MAX_MEDIA], поэтому отправитель
     * обязан приложить к сообщению ровно `min(frameCount, MAX_MEDIA)` элементов `media` с
     * идентификаторами [mediaId] от `0`. Разъедутся html и массив `media` — Telegram отвергнет
     * сообщение, а на пути первичной отправки это ещё и вечный ретрай.
     */
    fun render(
        data: RecordingNotificationData,
        description: DescriptionState,
        frameCount: Int,
        language: String,
    ): String {
        val head =
            buildString {
                append("<h2>").append(msgText(KEY_TITLE, language)).append("</h2>")
                append("<table bordered striped compact>")
                row(KEY_LABEL_CAMERA, trimRaw(data.camId, MAX_HEAD_FIELD_LENGTH), language)
                row(KEY_LABEL_FILE, trimRaw(data.fileName, MAX_HEAD_FIELD_LENGTH), language)
                row(KEY_LABEL_DETECTIONS, data.detectionsCount.toString(), language)
                row(KEY_LABEL_FRAMES, data.analyzedFramesCount.toString(), language)
                row(KEY_LABEL_ANALYZE_TIME, data.analyzeTimeSeconds.toString(), language)
                row(KEY_LABEL_RECORD_TS, data.recordTimestamp, language)
                row(KEY_LABEL_PROCESS_TS, data.processTimestamp, language)
                append("</table>")
                append(shortHtml(description, language))
                append(framesHtml(frameCount))
            }

        // Absent — подробностей нет вовсе. Failed — они не появятся, и причина уже сказана в <p>:
        // спойлер «Подробное описание» с той же строкой внутри обещает подробность и не отдаёт ничего.
        if (description == DescriptionState.Absent || description == DescriptionState.Failed) return head
        val open = "<details><summary>${msgText(KEY_DETAILS_SUMMARY, language)}</summary>"
        val budget = MAX_LENGTH - head.length - open.length - DETAILS_CLOSE.length
        val detailed =
            when (description) {
                // Текст бандла — наша собственная разметка, он уходит как есть и заведомо короток.
                DescriptionState.Pending -> msg.get(KEY_PLACEHOLDER_DETAILED, language)

                // Текст модели — чужой ввод: экранируется и режется по бюджету.
                is DescriptionState.Ready -> escapeAndTrim(description.result.detailed, budget)

                // Сюда не доходят — оба вернули head выше; ветка нужна для исчерпывающего when.
                DescriptionState.Absent, DescriptionState.Failed -> return head
            }
        return head + open + detailed + DETAILS_CLOSE
    }

    private fun StringBuilder.row(
        labelKey: String,
        value: String,
        language: String,
    ) {
        append("<tr><td>")
            .append(msgText(labelKey, language))
            .append("</td><td>")
            .append(escapeTelegramHtml(value))
            .append("</td></tr>")
    }

    private fun shortHtml(
        description: DescriptionState,
        language: String,
    ): String =
        when (description) {
            DescriptionState.Absent -> ""
            DescriptionState.Pending -> paragraph(msg.get(KEY_PLACEHOLDER_SHORT, language))
            DescriptionState.Failed -> paragraph(msg.get(KEY_FALLBACK, language))
            is DescriptionState.Ready -> paragraph(escapeAndTrim(description.result.short, MAX_SHORT_LENGTH))
        }

    /**
     * Строка бандла, которая по построению не разметка: заголовок, подписи ячеек, заголовок
     * раскрывашки. Три строки с `<i>` через неё НЕ идут — см. границу доверия в KDoc класса.
     */
    private fun msgText(
        key: String,
        language: String,
    ): String = escapeTelegramHtml(msg.get(key, language))

    /**
     * Обрезка СЫРОГО текста до экранирования — тем и отличается от [escapeAndTrim], что сущностей
     * тут ещё нет и рвать нечего. Суррогатную пару не раскалывает: половина пары даёт невалидный
     * UTF-8 на проводе.
     */
    private fun trimRaw(
        s: String,
        max: Int,
    ): String =
        when {
            s.length <= max -> s
            s[max - 1].isHighSurrogate() -> s.substring(0, max - 1)
            else -> s.substring(0, max)
        }

    /** Принимает УЖЕ подготовленный HTML: экранирование — забота вызывающего, см. границу доверия. */
    private fun paragraph(inner: String): String = "<p>$inner</p>"

    private fun framesHtml(frameCount: Int): String {
        val count = frameCount.coerceAtMost(MAX_MEDIA)
        return when {
            count == 0 -> ""
            count == 1 -> img(0)
            else -> (0 until count).joinToString(separator = "", prefix = "<tg-collage>", postfix = "</tg-collage>") { img(it) }
        }
    }

    private fun img(index: Int): String = """<img src="tg://photo?id=${mediaId(index)}"/>"""

    /**
     * Экранирует и, если не влезает в бюджет, обрезает так, чтобы не разорвать HTML-сущность
     * и не расколоть суррогатную пару.
     */
    private fun escapeAndTrim(
        text: String,
        budget: Int,
    ): String {
        if (budget <= 0) return ""
        val escaped = escapeTelegramHtml(text)
        if (escaped.length <= budget) return escaped
        var cutoff = budget - 1 // место под многоточие
        val lastAmp = escaped.lastIndexOf('&', startIndex = (cutoff - 1).coerceAtLeast(0))
        if (lastAmp >= 0) {
            val entityEnd = escaped.indexOf(';', startIndex = lastAmp)
            if (entityEnd < 0 || entityEnd >= cutoff) {
                cutoff = lastAmp
            }
        }
        if (cutoff > 0 && escaped[cutoff - 1].isHighSurrogate()) {
            cutoff -= 1
        }
        return escaped.substring(0, cutoff.coerceAtLeast(0)) + "…"
    }

    companion object {
        /** Лимит текста rich-сообщения по документации Bot API. */
        const val MAX_LENGTH = 32_768

        /** Потолок медиа в rich-сообщении; тот же предел стоит на LOCAL_VIZ_MAX_FRAMES (дефолт 10). */
        const val MAX_MEDIA = 50

        /**
         * Потолок текстового поля шапки. Ограничены оба свободных поля — `camId` и `fileName`, —
         * чтобы «шапка ограничена» было верно по построению, а не по арифметике, которую пришлось
         * бы пересчитывать при каждой правке таблицы.
         *
         * Повод конкретный: `fileName` — это `filePath.substringAfterLast("/")`, а
         * `substringAfterLast` БЕЗ разделителя возвращает строку целиком; колонка `file_path`
         * объявлена `varchar(16384)`, так что путь без слэша прошёл бы в шапку полностью и с
         * раскрытием `&` → `&amp;` увёл бы `budget` в минус, опустошив `<details>`.
         *
         * 256 — это `NAME_MAX` типичных Linux-ФС: любое настоящее имя файла проходит нетронутым,
         * обрезается только то, что именем файла быть не может. Оба поля идут исключительно в
         * ячейки таблицы; действия завязаны на `recordingId`, поэтому обрезка ничего не ломает.
         */
        const val MAX_HEAD_FIELD_LENGTH = 256

        /**
         * Потолок короткого описания. Настройка `APP_AI_DESCRIPTION_SHORT_MAX` по умолчанию 200 и
         * ограничена сверху `@Max(500)`, так что реальные описания эта граница не трогает — она
         * существует, чтобы `short` не мог съесть бюджет всего сообщения и оставить `<details>` пустым.
         */
        const val MAX_SHORT_LENGTH = 2_000

        /**
         * Идентификатор кадра. Одна и та же строка обязана попасть и в `<img src="tg://photo?id=…">`,
         * и в `InputRichMessageMedia.id`, иначе Telegram отвергнет сообщение.
         */
        fun mediaId(index: Int): String = "f$index"

        /**
         * Массив `media` по готовым идентификаторам кадров. Живёт рядом с [mediaId] намеренно:
         * html и `media` обязаны объявлять одни и те же слоты, а выражение это было выписано
         * дважды — в отправителе и в раннере правок, — то есть инвариант держался на том, что обе
         * копии одинаковы. Здесь же берётся и кламп до [MAX_MEDIA]: [render] молча ограничивает
         * число `<img>`, и без клампа список из 51 идентификатора дал бы html с `f0..f49` против
         * media с `f0..f50` — Telegram такое отвергает.
         */
        fun mediaFrom(ids: List<FileId>): List<InputRichMessageMedia> =
            ids.take(MAX_MEDIA).mapIndexed { index, id ->
                InputRichMessageMedia(mediaId(index), TelegramMediaPhoto(id))
            }

        private const val DETAILS_CLOSE = "</details>"

        private const val KEY_TITLE = "notification.recording.title"
        private const val KEY_LABEL_CAMERA = "notification.recording.label.camera"
        private const val KEY_LABEL_FILE = "notification.recording.label.file"
        private const val KEY_LABEL_DETECTIONS = "notification.recording.label.detections"
        private const val KEY_LABEL_FRAMES = "notification.recording.label.frames"
        private const val KEY_LABEL_ANALYZE_TIME = "notification.recording.label.analyze.time"
        private const val KEY_LABEL_RECORD_TS = "notification.recording.label.record.timestamp"
        private const val KEY_LABEL_PROCESS_TS = "notification.recording.label.process.timestamp"
        private const val KEY_DETAILS_SUMMARY = "ai.description.details.summary"
        private const val KEY_PLACEHOLDER_SHORT = "ai.description.placeholder.short"
        private const val KEY_PLACEHOLDER_DETAILED = "ai.description.placeholder.detailed"
        private const val KEY_FALLBACK = "ai.description.fallback.unavailable"
    }
}
