package ru.zinin.frigate.analyzer.telegram.service.impl

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
 * **Граница доверия.** Строки из бандла сообщений (заголовок, подписи ячеек, плейсхолдеры, fallback,
 * заголовок раскрывашки) — наша собственная разметка и вставляются как есть: `ai.description.placeholder.short`
 * это `⏳ <i>AI анализирует кадры…</i>`, и экранирование превратило бы курсив в литеральные `&lt;i&gt;`.
 * Всё, что пришло извне — поля [RecordingNotificationData] и оба текста модели, — экранируется.
 */
@Component
class RichNotificationRenderer(
    private val msg: MessageResolver,
) {
    fun render(
        data: RecordingNotificationData,
        description: DescriptionState,
        frameCount: Int,
        language: String,
    ): String {
        val head =
            buildString {
                append("<h2>").append(msg.get(KEY_TITLE, language)).append("</h2>")
                append("<table bordered striped compact>")
                row(KEY_LABEL_CAMERA, data.camId, language)
                row(KEY_LABEL_FILE, data.fileName, language)
                row(KEY_LABEL_DETECTIONS, data.detectionsCount.toString(), language)
                row(KEY_LABEL_FRAMES, data.analyzedFramesCount.toString(), language)
                row(KEY_LABEL_ANALYZE_TIME, data.analyzeTimeSeconds.toString(), language)
                row(KEY_LABEL_RECORD_TS, data.recordTimestamp, language)
                row(KEY_LABEL_PROCESS_TS, data.processTimestamp, language)
                append("</table>")
                append(shortHtml(description, language))
                append(framesHtml(frameCount))
            }

        if (description == DescriptionState.Absent) return head
        val open = "<details><summary>${msg.get(KEY_DETAILS_SUMMARY, language)}</summary>"
        val budget = MAX_LENGTH - head.length - open.length - DETAILS_CLOSE.length
        val detailed =
            when (description) {
                // Тексты бандла — наша собственная разметка, они уходят как есть и заведомо коротки.
                DescriptionState.Pending -> msg.get(KEY_PLACEHOLDER_DETAILED, language)

                DescriptionState.Failed -> msg.get(KEY_FALLBACK, language)

                // Текст модели — чужой ввод: экранируется и режется по бюджету.
                is DescriptionState.Ready -> escapeAndTrim(description.result.detailed, budget)

                DescriptionState.Absent -> return head
            }
        return head + open + detailed + DETAILS_CLOSE
    }

    private fun StringBuilder.row(
        labelKey: String,
        value: String,
        language: String,
    ) {
        append("<tr><td>")
            .append(msg.get(labelKey, language))
            .append("</td><td>")
            .append(escape(value))
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
            is DescriptionState.Ready -> paragraph(escape(description.result.short))
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
     * Экранирование для Telegram HTML. Кавычки не экранируются: значения никогда не попадают
     * в атрибуты — единственные атрибуты, которые мы строим, это наши же `tg://photo?id=fN`.
     */
    private fun escape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * Экранирует и, если не влезает в бюджет, обрезает так, чтобы не разорвать HTML-сущность
     * и не расколоть суррогатную пару.
     */
    private fun escapeAndTrim(
        text: String,
        budget: Int,
    ): String {
        if (budget <= 0) return ""
        val escaped = escape(text)
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

        /** Потолок медиа в rich-сообщении. Наш собственный максимум кадров — 10. */
        const val MAX_MEDIA = 50

        /**
         * Идентификатор кадра. Одна и та же строка обязана попасть и в `<img src="tg://photo?id=…">`,
         * и в `InputRichMessageMedia.id`, иначе Telegram отвергнет сообщение.
         */
        fun mediaId(index: Int): String = "f$index"

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
