package ru.zinin.frigate.analyzer.telegram.service.impl

import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import org.springframework.context.support.StaticMessageSource
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.model.DescriptionState
import ru.zinin.frigate.analyzer.telegram.service.model.RecordingNotificationData
import java.util.Locale
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichNotificationRendererTest {
    private val msg =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )
    private val renderer = RichNotificationRenderer(msg)

    private fun data(
        camId: String = "driveway",
        fileName: String = "2026-08-31_21-15-03.mp4",
    ) = RecordingNotificationData(
        camId = camId,
        fileName = fileName,
        detectionsCount = 3,
        analyzedFramesCount = 12,
        analyzeTimeSeconds = 4,
        recordTimestamp = "31 августа 2026 г., 21:15",
        processTimestamp = "31 августа 2026 г., 21:20",
    )

    @Test
    fun `renders heading and metadata table`() {
        val html = renderer.render(data(), DescriptionState.Absent, frameCount = 0, language = "ru")

        assertContains(html, "<h2>")
        assertContains(html, "<table bordered striped compact>")
        assertContains(html, "<tr><td>Камера</td><td>driveway</td></tr>")
        assertContains(html, "<tr><td>Время обработки, сек</td><td>4</td></tr>")
        assertContains(html, "<tr><td>Запись</td><td>31 августа 2026 г., 21:15</td></tr>")
    }

    @Test
    fun `escapes html special characters in values`() {
        val html =
            renderer.render(
                data(camId = "cam<1>&2", fileName = "a<b>.mp4"),
                DescriptionState.Absent,
                frameCount = 0,
                language = "ru",
            )

        assertContains(html, "cam&lt;1&gt;&amp;2")
        assertFalse(html.contains("cam<1>"), "raw angle brackets must not survive escaping")
    }

    @Test
    fun `a bundle label is escaped and cannot smuggle markup into the message`() {
        // Подписи ячеек, заголовок и заголовок раскрывашки разметкой не бывают, а вот сломать
        // HTML правкой .properties легко: отказ Telegram был бы детерминированным, а первичная
        // отправка ретраится бесконечно и держит единственного потребителя очереди.
        val poisoned =
            StaticMessageSource().apply {
                addMessage("notification.recording.label.camera", Locale.forLanguageTag("ru"), "R&D <камера>")
            }
        val html =
            RichNotificationRenderer(MessageResolver(poisoned))
                .render(data(), DescriptionState.Absent, frameCount = 0, language = "ru")

        assertContains(html, "R&amp;D &lt;камера&gt;")
        assertFalse(html.contains("<камера>"), "a label must never reach Telegram as markup")
    }

    @Test
    fun `the three markup-carrying bundle strings still go in raw`() {
        // Обратная сторона: у этих трёх ключей курсив — часть значения, экранирование показало бы
        // пользователю литеральные &lt;i&gt;.
        val pending = renderer.render(data(), DescriptionState.Pending, frameCount = 1, language = "ru")
        assertContains(pending, msg.get("ai.description.placeholder.short", "ru"))
        assertContains(pending, msg.get("ai.description.placeholder.detailed", "ru"))

        val failed = renderer.render(data(), DescriptionState.Failed, frameCount = 1, language = "ru")
        assertContains(failed, msg.get("ai.description.fallback.unavailable", "ru"))
    }

    @Test
    fun `single frame renders as plain img, two or more as collage`() {
        val one = renderer.render(data(), DescriptionState.Absent, frameCount = 1, language = "ru")
        assertContains(one, """<img src="tg://photo?id=f0"/>""")
        assertFalse(one.contains("<tg-collage>"), "one frame needs no collage")

        val three = renderer.render(data(), DescriptionState.Absent, frameCount = 3, language = "ru")
        assertContains(three, "<tg-collage>")
        assertContains(three, """<img src="tg://photo?id=f2"/>""")
    }

    @Test
    fun `zero frames renders no media at all`() {
        val html = renderer.render(data(), DescriptionState.Absent, frameCount = 0, language = "ru")

        assertFalse(html.contains("<img"), "no frames means no img tags")
        assertFalse(html.contains("<tg-collage>"), "no frames means no collage")
    }

    @Test
    fun `absent description renders neither paragraph nor details`() {
        val html = renderer.render(data(), DescriptionState.Absent, frameCount = 2, language = "ru")

        assertFalse(html.contains("<details>"), "disabled description must not render a details block")
        assertFalse(html.contains("Подробное описание"), "disabled description must not render a summary")
    }

    @Test
    fun `pending description renders placeholders`() {
        val html = renderer.render(data(), DescriptionState.Pending, frameCount = 2, language = "ru")

        // Плейсхолдеры бандла несут собственную разметку (<i>…</i>) и обязаны дойти неэкранированными.
        assertContains(html, msg.get("ai.description.placeholder.short", "ru"))
        assertContains(html, "<details><summary>Подробное описание</summary>")
        assertContains(html, msg.get("ai.description.placeholder.detailed", "ru"))
    }

    @Test
    fun `ready description renders model text`() {
        val html =
            renderer.render(
                data(),
                DescriptionState.Ready(DescriptionResult(short = "Человек у ворот", detailed = "Подробный текст")),
                frameCount = 2,
                language = "ru",
            )

        assertContains(html, "<p>Человек у ворот</p>")
        assertContains(html, "<details><summary>Подробное описание</summary>Подробный текст</details>")
    }

    @Test
    fun `model text is escaped in both slots`() {
        // Тексты модели — единственный вход, который никто не валидирует: их сочиняет LLM.
        // Экранирование обеих веток рендерер выполняет, но до сих пор это не было закреплено
        // тестом — проверялись только camId и fileName.
        val html =
            renderer.render(
                data(),
                DescriptionState.Ready(
                    DescriptionResult(
                        short = "<b>человек</b> & пёс",
                        detailed = "</details><tg-button>жми</tg-button>",
                    ),
                ),
                frameCount = 2,
                language = "ru",
            )

        val short = html.substringAfter("</table><p>").substringBefore("</p>")
        assertEquals("&lt;b&gt;человек&lt;/b&gt; &amp; пёс", short, "short must be escaped")

        val detailed = html.substringAfter("</summary>").substringBefore("</details>")
        assertEquals("&lt;/details&gt;&lt;tg-button&gt;жми&lt;/tg-button&gt;", detailed, "detailed must be escaped")

        // Ни один тег модели не должен уцелеть как разметка — иначе она может закрыть наш
        // <details> или подсунуть конструкцию Bot API 10.3, которую сервер отвергнет.
        assertFalse(html.contains("<b>"), "raw <b> from the model must not survive")
        assertFalse(html.contains("<tg-button>"), "raw <tg-button> from the model must not survive")
        assertEquals(1, html.split("</details>").size - 1, "the model must not be able to close our details block")
    }

    @Test
    fun `failed description renders the fallback once and no details block`() {
        val fallback = msg.get("ai.description.fallback.unavailable", "ru")

        val html = renderer.render(data(), DescriptionState.Failed, frameCount = 2, language = "ru")

        assertEquals(1, html.split(fallback).size - 1, "the fallback belongs in the paragraph, once")
        // Спойлер «Подробное описание» с тем же текстом внутри обещал подробность и не отдавал
        // ничего. Absent ведёт себя так же — блок не строится, когда показывать нечего.
        assertFalse(html.contains("<details>"), "no details block when there is nothing to put in it")
    }

    @Test
    fun `oversized detailed text is trimmed to the rich message limit`() {
        val html =
            renderer.render(
                data(),
                DescriptionState.Ready(DescriptionResult(short = "кратко", detailed = "д".repeat(40_000))),
                frameCount = 2,
                language = "ru",
            )

        assertTrue(html.length <= 32_768, "rendered message must fit the rich message limit, was ${html.length}")
        assertTrue(html.endsWith("</details>"), "trimming must not break the details block")
    }

    @Test
    fun `oversized short description does not swallow the detailed block`() {
        val html =
            renderer.render(
                data(),
                DescriptionState.Ready(DescriptionResult(short = "к".repeat(40_000), detailed = "подробности модели")),
                frameCount = 2,
                language = "ru",
            )

        assertTrue(html.length <= 32_768, "rendered message must fit the rich message limit, was ${html.length}")
        assertContains(
            html,
            "подробности модели",
            message = "an oversized short must not eat the budget of the details block",
        )
    }

    @Test
    fun `entity is never split by trimming`() {
        val html =
            renderer.render(
                data(),
                DescriptionState.Ready(DescriptionResult(short = "кратко", detailed = "<".repeat(40_000))),
                frameCount = 2,
                language = "ru",
            )

        val detailed = html.substringAfter("</summary>").substringBefore("</details>").removeSuffix("…")
        assertFalse(
            Regex("&[a-z]{1,4}$").containsMatchIn(detailed),
            "a trailing half-entity would break Telegram HTML, got tail: ${detailed.takeLast(8)}",
        )
    }

    @Test
    fun `an unbounded file name cannot blow the message budget`() {
        // filePath.substringAfterLast("/") БЕЗ слэша отдаёт путь целиком, а колонка держит 16384.
        val html =
            renderer.render(
                data(camId = "c".repeat(16_384), fileName = "&".repeat(16_384)),
                DescriptionState.Ready(DescriptionResult(short = "коротко", detailed = "подробно")),
                frameCount = 2,
                language = "ru",
            )

        assertTrue(html.length <= RichNotificationRenderer.MAX_LENGTH, "the whole message stays within the limit")
        assertContains(html, "подробно", message = "a bounded head leaves the details block its budget")
    }

    @Test
    fun `media id is stable and zero based`() {
        assertEquals("f0", RichNotificationRenderer.mediaId(0))
        assertEquals("f9", RichNotificationRenderer.mediaId(9))
    }
}
