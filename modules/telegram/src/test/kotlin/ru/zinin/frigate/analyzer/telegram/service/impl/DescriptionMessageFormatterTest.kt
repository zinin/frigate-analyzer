package ru.zinin.frigate.analyzer.telegram.service.impl

import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DescriptionMessageFormatterTest {
    private val resolver =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )
    private val formatter = DescriptionMessageFormatter(resolver)

    @Test
    fun `escapes HTML specials in short and detailed`() {
        val result = DescriptionResult(short = "A <b>car</b> & person", detailed = "Full <text>")
        val caption = formatter.captionSuccess(baseText = "base", result = result, language = "en")
        assertTrue(caption.contains("&lt;b&gt;car&lt;/b&gt; &amp; person"))
        assertTrue(!caption.contains("<b>car</b>"))
    }

    @Test
    fun `escapes HTML in baseText too (camId or filePath may contain specials)`() {
        val result = DescriptionResult(short = "s", detailed = "d")
        val caption =
            formatter.captionSuccess(
                baseText = "Zone <Entrance> & gate",
                result = result,
                language = "en",
            )
        assertTrue(caption.contains("Zone &lt;Entrance&gt; &amp; gate"))
        assertTrue(!caption.contains("<Entrance>"))
    }

    @Test
    fun `expandableBlockquoteSuccess escapes HTML in detailed`() {
        val result = DescriptionResult("s", "a <b> & <c>")
        val block = formatter.expandableBlockquoteSuccess(result, "en")
        assertEquals("<blockquote expandable>a &lt;b&gt; &amp; &lt;c&gt;</blockquote>", block)
    }

    @Test
    fun `placeholderShort returns HTML-safe language-specific string`() {
        val pl = formatter.placeholderShort("ru")
        assertTrue(pl.contains("AI анализирует"))
    }

    @Test
    fun `expandableBlockquoteSuccess wraps detailed in blockquote`() {
        val result = DescriptionResult(short = "s", detailed = "Detailed text")
        val block = formatter.expandableBlockquoteSuccess(result, "en")
        assertEquals("<blockquote expandable>Detailed text</blockquote>", block)
    }

    @Test
    fun `expandableBlockquoteFallback uses localized unavailable text`() {
        val block = formatter.expandableBlockquoteFallback("ru")
        assertTrue(block.startsWith("<blockquote expandable>"))
        assertTrue(block.endsWith("</blockquote>"))
        assertTrue(block.contains("Описание недоступно"))
    }

    @Test
    fun `captionSuccess appends short under base with blank line`() {
        val result = DescriptionResult(short = "two cars", detailed = "ignored")
        val caption = formatter.captionSuccess(baseText = "base text", result = result, language = "en")
        assertEquals("base text\n\ntwo cars", caption)
    }

    @Test
    fun `captionFallback appends fallback text under base`() {
        val caption = formatter.captionFallback(baseText = "base", language = "en")
        assertTrue(caption.startsWith("base\n\n"))
        assertTrue(caption.contains("Description unavailable"))
    }

    @Test
    fun `captionInitialPlaceholder appends placeholder under base`() {
        val caption = formatter.captionInitialPlaceholder(baseText = "base", language = "en")
        assertTrue(caption.startsWith("base\n\n"))
        assertTrue(caption.contains("analyzing"))
    }
}
