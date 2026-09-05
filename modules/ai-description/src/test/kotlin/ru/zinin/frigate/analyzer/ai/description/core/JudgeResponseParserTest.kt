package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.JudgeVerdict
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JudgeResponseParserTest {
    private val parser = JudgeResponseParser(TestObjectMappers.internalMapper())

    private fun parse(raw: String) = parser.parse(raw, maxSnoozeMinutes = 30)

    @Test
    fun `parses a full publish verdict`() {
        val verdict =
            parse(
                """{"verdict":"PUBLISH","reason":"NEW_EVENT","confidence":0.9,""" +
                    """"summary":"Человек у калитки.","snooze_minutes":15,"wanted":""}""",
            )
        assertEquals(JudgeVerdict.Decision.PUBLISH, verdict.decision)
        assertEquals(JudgeVerdict.Reason.NEW_EVENT, verdict.reason)
        assertEquals(0.9, verdict.confidence)
        assertEquals("Человек у калитки.", verdict.summary)
        assertEquals(15, verdict.snoozeMinutes)
        assertEquals("", verdict.wanted)
    }

    @Test
    fun `every suppress reason is accepted with SUPPRESS`() {
        for (reason in listOf("FALSE_POSITIVE", "STATIC_OBJECT", "DUPLICATE")) {
            assertEquals(JudgeVerdict.Reason.valueOf(reason), parse("""{"verdict":"SUPPRESS","reason":"$reason","summary":"x"}""").reason)
        }
    }

    @Test
    fun `publish with a suppress reason is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"verdict":"PUBLISH","reason":"DUPLICATE","summary":"x"}""") }
    }

    @Test
    fun `suppress with a publish reason is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"verdict":"SUPPRESS","reason":"NEW_EVENT","summary":"x"}""") }
    }

    @Test
    fun `unknown verdict, unknown reason and missing fields are InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"verdict":"MAYBE","reason":"NEW_EVENT","summary":"x"}""") }
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"verdict":"PUBLISH","reason":"WHATEVER","summary":"x"}""") }
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"reason":"NEW_EVENT","summary":"x"}""") }
        assertFailsWith<DescriptionException.InvalidResponse> { parse("not json at all") }
    }

    @Test
    fun `snooze is clamped to the ceiling and negatives or garbage become zero`() {
        assertEquals(30, parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x","snooze_minutes":720}""").snoozeMinutes)
        assertEquals(0, parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x","snooze_minutes":-5}""").snoozeMinutes)
        assertEquals(0, parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x","snooze_minutes":"soon"}""").snoozeMinutes)
        assertEquals(0, parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x"}""").snoozeMinutes)
    }

    @Test
    fun `confidence outside 0 to 1 or non-numeric becomes null`() {
        assertNull(parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x","confidence":1.7}""").confidence)
        assertNull(parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x","confidence":"high"}""").confidence)
    }

    @Test
    fun `summary and wanted are truncated to 512 characters and default to empty`() {
        val long = "a".repeat(600)
        val verdict = parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"$long","wanted":"$long"}""")
        assertEquals(512, verdict.summary.length)
        assertEquals(512, verdict.wanted.length)
        assertEquals("", parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE"}""").summary)
    }

    @Test
    fun `JSON embedded in prose is extracted`() {
        val verdict = parse("""Sure! {"verdict":"SUPPRESS","reason":"STATIC_OBJECT","summary":"Припаркованная машина."} Done.""")
        assertEquals(JudgeVerdict.Reason.STATIC_OBJECT, verdict.reason)
    }
}
