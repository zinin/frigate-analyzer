package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResultNormalizerTest {
    @Test
    fun `returns both fields unchanged when within limits`() {
        val result = ResultNormalizer.normalize("Two cars.", "Two cars entering the yard.", 200, 1500)
        assertEquals(DescriptionResult("Two cars.", "Two cars entering the yard."), result)
    }

    @Test
    fun `truncates short longer than limit with ellipsis`() {
        val result = ResultNormalizer.normalize("a".repeat(250), "d", 200, 1500)
        assertEquals(200, result.short.length)
        assertEquals("…", result.short.last().toString())
    }

    @Test
    fun `truncates detailed longer than limit with ellipsis`() {
        val result = ResultNormalizer.normalize("s", "b".repeat(2000), 200, 1500)
        assertEquals(1500, result.detailed.length)
        assertEquals("…", result.detailed.last().toString())
    }

    @Test
    fun `never splits a surrogate pair at the cut`() {
        // 198 ASCII chars, then an astral emoji (two UTF-16 units), then filler: the naive cut at
        // index 199 would land between the high and the low surrogate.
        val text = "a".repeat(198) + "😀" + "bbb"
        val result = ResultNormalizer.normalize(text, "d", 200, 1500)
        assertEquals(199, result.short.length)
        assertTrue(result.short.none { it.isSurrogate() }, "no lone surrogate may survive the cut")
        assertEquals("…", result.short.last().toString())
    }

    @Test
    fun `blank short is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { ResultNormalizer.normalize("  ", "d", 200, 1500) }
    }

    @Test
    fun `null detailed is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { ResultNormalizer.normalize("s", null, 200, 1500) }
    }
}
