package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaudeResponseParserTest {
    private val mapper = ObjectMapper().registerKotlinModule()
    private val parser = ClaudeResponseParser(mapper)

    private fun parse(
        raw: String,
        shortMax: Int = 200,
        detailedMax: Int = 1500,
    ) = parser.parse(raw, shortMax, detailedMax)

    @Test
    fun `parses valid JSON`() {
        val result = parse("""{"short": "Two cars.", "detailed": "Two cars entering the yard."}""")
        assertEquals("Two cars.", result.short)
        assertEquals("Two cars entering the yard.", result.detailed)
    }

    @Test
    fun `throws InvalidResponse on non-JSON`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("not JSON at all") }
    }

    @Test
    fun `throws InvalidResponse on missing short key`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"detailed": "foo"}""") }
    }

    @Test
    fun `throws InvalidResponse on missing detailed key`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"short": "foo"}""") }
    }

    @Test
    fun `throws InvalidResponse on blank short value`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"short": "", "detailed": "foo"}""") }
    }

    @Test
    fun `extracts JSON embedded in prose`() {
        val raw = """Here is the analysis: {"short": "X", "detailed": "Y"} — that's it."""
        val result = parse(raw)
        assertEquals("X", result.short)
        assertEquals("Y", result.detailed)
    }

    @Test
    fun `truncates short longer than limit with ellipsis`() {
        val longShort = "a".repeat(250)
        val result = parse("""{"short": "$longShort", "detailed": "d"}""", shortMax = 200)
        assertEquals(200, result.short.length)
        assertEquals("…", result.short.last().toString())
    }

    @Test
    fun `truncates detailed longer than limit with ellipsis`() {
        val longDetailed = "b".repeat(2000)
        val result = parse("""{"short": "s", "detailed": "$longDetailed"}""", detailedMax = 1500)
        assertEquals(1500, result.detailed.length)
        assertEquals("…", result.detailed.last().toString())
    }

    @Test
    fun `throws InvalidResponse on blank detailed value`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"short": "foo", "detailed": ""}""") }
    }

    @Test
    fun `prose with multiple JSON-looking blocks grabs span between first and last brace`() {
        // Known limitation of extractJsonBlock: `indexOf('{')` .. `lastIndexOf('}')` grabs both
        // groups and their connective prose, so Jackson rejects it as InvalidResponse. Claude is
        // instructed to return a bare JSON object (no prose), so this is a fail-closed safety test
        // — if someone ever rewrites extractJsonBlock to be more lenient, this test guards against
        // silently accepting malformed input.
        val raw = """Example: {"foo":"bar"} Now the real answer: {"short":"X","detailed":"Y"}"""
        assertFailsWith<DescriptionException.InvalidResponse> { parse(raw) }
    }
}
