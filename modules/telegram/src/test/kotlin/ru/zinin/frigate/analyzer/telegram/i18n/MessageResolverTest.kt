package ru.zinin.frigate.analyzer.telegram.i18n

import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import java.util.Locale
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MessageResolverTest {
    private val messageSource =
        ReloadableResourceBundleMessageSource().apply {
            setBasename("classpath:messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
            setDefaultLocale(Locale.forLanguageTag("en"))
        }
    private val resolver = MessageResolver(messageSource)

    @Test
    fun `get returns Russian text for ru locale`() {
        val result = resolver.get("common.cancel", "ru")
        assertEquals("Отмена", result)
    }

    @Test
    fun `get returns English text for en locale`() {
        val result = resolver.get("common.cancel", "en")
        assertEquals("Cancel", result)
    }

    @Test
    fun `get returns raw key for missing key`() {
        val result = resolver.get("nonexistent.key", "en")
        assertEquals("nonexistent.key", result)
    }

    @Test
    fun `get substitutes arguments correctly`() {
        val result = resolver.get("command.removeuser.removed", "en", "testuser")
        assertContains(result, "testuser")
    }

    @Test
    fun `get falls back to English for unknown locale`() {
        val result = resolver.get("common.cancel", "de")
        assertEquals("Cancel", result)
    }
}
