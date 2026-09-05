package ru.zinin.frigate.analyzer.ai.description.core

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonBlockExtractorTest {
    @Test
    fun `bare object is returned as is`() {
        assertEquals("""{"short":"a"}""", JsonBlockExtractor.extract("""  {"short":"a"}  """))
    }

    @Test
    fun `markdown fence is stripped`() {
        val raw = "```json\n{\"short\": \"a\", \"detailed\": \"b\"}\n```"
        assertEquals("""{"short": "a", "detailed": "b"}""", JsonBlockExtractor.extract(raw))
    }

    @Test
    fun `prose around the object is dropped`() {
        val raw = "Here you go:\n{\"short\": \"a\"}\nHope that helps."
        assertEquals("""{"short": "a"}""", JsonBlockExtractor.extract(raw))
    }

    @Test
    fun `text without braces is returned unchanged`() {
        assertEquals("no json here", JsonBlockExtractor.extract("  no json here  "))
    }
}
