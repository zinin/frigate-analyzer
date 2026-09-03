package ru.zinin.frigate.analyzer.ai.description.grok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GrokPromptBuilderTest {
    private val builder = GrokPromptBuilder()

    @Test
    fun `introduction names the language`() {
        assertTrue(builder.introduction("en").contains("in English"))
        assertTrue(builder.introduction("ru").contains("in Russian"))
    }

    @Test
    fun `introduction announces the frames block`() {
        assertTrue(builder.introduction("en").endsWith("Frames (in chronological order):"))
    }

    @Test
    fun `introduction rejects unknown language`() {
        assertFailsWith<IllegalStateException> { builder.introduction("de") }
    }

    @Test
    fun `frame label carries the frame index`() {
        assertEquals("Frame 17:", builder.frameLabel(17))
    }

    @Test
    fun `rules carry both limits and both field names`() {
        val rules = builder.rules(150, 800)
        assertTrue(rules.contains("\"short\" must not exceed 150 characters"))
        assertTrue(rules.contains("\"detailed\" must not exceed 800 characters"))
        assertTrue(rules.contains("No markdown"))
    }

    @Test
    fun `rules ask for a plain JSON object so models without schema support can answer`() {
        val rules = builder.rules(150, 800)
        assertTrue(rules.contains("Return ONLY this JSON object"))
        assertTrue(rules.contains("""{"short": "...", "detailed": "..."}"""))
    }

    @Test
    fun `system prompt forbids tools and asks for the JSON object`() {
        assertTrue(GrokPromptBuilder.SYSTEM_PROMPT.contains("JSON object"))
        assertTrue(GrokPromptBuilder.SYSTEM_PROMPT.contains("Do not call tools"))
    }
}
