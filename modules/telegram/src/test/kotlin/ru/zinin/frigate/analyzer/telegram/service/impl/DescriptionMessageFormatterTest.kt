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

    @Test
    fun `captionBasePlain returns HTML-escaped baseText with no placeholder suffix`() {
        val caption = formatter.captionBasePlain(baseText = "Zone <A> & gate")
        assertEquals("Zone &lt;A&gt; &amp; gate", caption)
    }

    @Test
    fun `mediaGroupText combines short and expandable blockquote on success`() {
        val result = DescriptionResult(short = "two cars", detailed = "two cars approaching gate")
        val text = formatter.mediaGroupText(baseText = "base", outcome = Result.success(result), language = "en")
        assertEquals("base\n\ntwo cars\n\n<blockquote expandable>two cars approaching gate</blockquote>", text)
    }

    @Test
    fun `mediaGroupText uses fallback text on failure`() {
        val text = formatter.mediaGroupText(baseText = "base", outcome = Result.failure(RuntimeException()), language = "en")
        assertTrue(text.contains("Description unavailable"))
        assertTrue(text.contains("<blockquote expandable>"))
        assertTrue(text.contains("</blockquote>"))
    }

    @Test
    fun `mediaGroupText trims detailed to honour 4096-char editMessageText limit`() {
        // Worst-case short (1024 chars of base+short) + 2 newline + 35 blockquote overhead → detailed must fit in ~3035.
        val longDetailed = "d".repeat(5000)
        val result = DescriptionResult(short = "s", detailed = longDetailed)
        val text = formatter.mediaGroupText(baseText = "base", outcome = Result.success(result), language = "en")
        assertTrue(text.length <= 4096, "Combined text must fit 4096 limit, got ${text.length}")
        assertTrue(text.endsWith("</blockquote>"), "Wrapper must remain intact after truncation: $text")
    }

    @Test
    fun `expandableBlockquoteSuccess trims detailed to honour 4096-char editMessageText limit`() {
        // HTML-dense input that blows up after escape: 3500 ampersands → 3500×5 = 17500 chars > 4096.
        // Result must fit under 4096 and the blockquote wrapper must stay intact.
        val longDetailed = "&".repeat(3500)
        val result = DescriptionResult(short = "s", detailed = longDetailed)
        val block = formatter.expandableBlockquoteSuccess(result, "en")
        assertTrue(block.length <= 4096, "expandableBlockquoteSuccess must fit 4096 limit, got ${block.length}")
        assertTrue(block.startsWith("<blockquote expandable>"))
        assertTrue(block.endsWith("</blockquote>"), "Wrapper must remain intact after truncation: $block")
    }

    @Test
    fun `expandableBlockquoteSuccess does not split UTF-16 surrogate pairs when trimming detailed`() {
        // Pack with astral-plane chars (🚗 = U+1F697 = 2 UTF-16 chars) past the budget so the naive
        // cutoff could land on a high-surrogate; verify no isolated surrogate in the output.
        val longDetailed = "a".repeat(3000) + "🚗".repeat(700)
        val result = DescriptionResult(short = "s", detailed = longDetailed)
        val block = formatter.expandableBlockquoteSuccess(result, "en")
        assertTrue(block.length <= 4096)
        block.forEachIndexed { i, ch ->
            if (ch.isHighSurrogate()) {
                assertTrue(i + 1 < block.length && block[i + 1].isLowSurrogate(), "isolated high-surrogate at $i")
            }
            if (ch.isLowSurrogate()) {
                assertTrue(i > 0 && block[i - 1].isHighSurrogate(), "isolated low-surrogate at $i")
            }
        }
    }

    @Test
    fun `mediaGroupText does not split UTF-16 surrogate pairs when trimming detailed`() {
        // Force the entity-unaware truncation path to land exactly on a high-surrogate boundary:
        // build `detailed` so that its length is just over the budget, and the character at the
        // budget boundary is a high-surrogate (🚗 = U+1F697 = two UTF-16 chars).
        val filler = "a".repeat(3000)
        val longDetailed = filler + "🚗".repeat(500)
        val result = DescriptionResult(short = "s", detailed = longDetailed)
        val text = formatter.mediaGroupText(baseText = "base", outcome = Result.success(result), language = "en")
        assertTrue(text.length <= 4096)
        // No isolated surrogate anywhere in the output.
        text.forEachIndexed { i, ch ->
            if (ch.isHighSurrogate()) {
                assertTrue(i + 1 < text.length && text[i + 1].isLowSurrogate(), "isolated high-surrogate at $i")
            }
            if (ch.isLowSurrogate()) {
                assertTrue(i > 0 && text[i - 1].isHighSurrogate(), "isolated low-surrogate at $i")
            }
        }
    }
}
