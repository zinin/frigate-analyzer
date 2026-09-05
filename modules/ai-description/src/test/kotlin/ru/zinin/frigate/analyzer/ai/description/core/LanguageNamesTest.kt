package ru.zinin.frigate.analyzer.ai.description.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LanguageNamesTest {
    @Test
    fun `maps ru and en`() {
        assertEquals("Russian", LanguageNames.of("ru"))
        assertEquals("English", LanguageNames.of("en"))
    }

    @Test
    fun `is case-insensitive`() {
        assertEquals("Russian", LanguageNames.of("RU"))
    }

    @Test
    fun `rejects unknown code`() {
        assertFailsWith<IllegalStateException> { LanguageNames.of("de") }
    }
}
