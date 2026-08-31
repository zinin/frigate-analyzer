package ru.zinin.frigate.analyzer.telegram.service.impl

import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
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
    fun `failed description renders fallback in both slots`() {
        val fallback = msg.get("ai.description.fallback.unavailable", "ru")

        val html = renderer.render(data(), DescriptionState.Failed, frameCount = 2, language = "ru")

        assertEquals(2, html.split(fallback).size - 1, "fallback goes into both the paragraph and the details")
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
    fun `media id is stable and zero based`() {
        assertEquals("f0", RichNotificationRenderer.mediaId(0))
        assertEquals("f9", RichNotificationRenderer.mediaId(9))
    }
}
