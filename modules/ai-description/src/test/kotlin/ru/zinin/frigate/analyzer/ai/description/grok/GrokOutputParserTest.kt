package ru.zinin.frigate.analyzer.ai.description.grok

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrokOutputParserTest {
    private val parser = GrokOutputParser(TestObjectMappers.internalMapper())

    private val success =
        """
        {"text":"{\"short\":\"Car\",\"detailed\":\"A car in the yard.\"}","stopReason":"end_turn",
         "sessionId":"01a06332-cee4-7a82-ac73-8556a6ea21c4","requestId":"r1",
         "usage":{"input_tokens":3048,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,
                  "output_tokens":120,"reasoning_tokens":119,"total_tokens":3168},
         "modelUsage":{"grok-4.6":{"inputTokens":3048,"outputTokens":120,"modelCalls":1,"costUSD":0.0013}},
         "total_cost_usd":0.0013,
         "structuredOutput":{"short":"Car","detailed":"A car in the yard."}}
        """.trimIndent()

    @Test
    fun `parses structured output and metadata`() {
        val output = parser.parse(success)

        assertEquals("end_turn", output.stopReason)
        assertEquals("01a06332-cee4-7a82-ac73-8556a6ea21c4", output.sessionId)
        assertEquals("Car", output.short)
        assertEquals("A car in the yard.", output.detailed)
        assertTrue(output.usageSummary.contains("input_tokens=3048"))
        assertTrue(output.usageSummary.contains("output_tokens=120"))
        assertTrue(output.usageSummary.contains("total_cost_usd=0.0013"))
    }

    @Test
    fun `missing structured output yields null fields`() {
        val output = parser.parse("""{"text":"sorry","stopReason":"max_tokens","sessionId":"s"}""")

        assertNull(output.short)
        assertNull(output.detailed)
        assertEquals("max_tokens", output.stopReason)
        assertTrue(output.usageSummary.contains("usage=absent"))
    }

    @Test
    fun `non-json stdout is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parser.parse("Segmentation fault") }
    }

    @Test
    fun `json array is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parser.parse("[1,2]") }
    }

    @Test
    fun `errorMessage reads the error envelope`() {
        val stdout = """{"type":"error","message":"Not signed in. To authenticate without a browser, run:\n  grok login --device-code"}"""
        assertEquals("Not signed in. To authenticate without a browser, run:\n  grok login --device-code", parser.errorMessage(stdout))
    }

    @Test
    fun `errorMessage is null for a success object or garbage`() {
        assertNull(parser.errorMessage(success))
        assertNull(parser.errorMessage("garbage"))
        assertNull(parser.errorMessage(""))
    }

    @Test
    fun `numeric structured fields are not descriptions`() {
        val output =
            parser.parse("""{"stopReason":"end_turn","structuredOutput":{"short":1,"detailed":true}}""")
        assertNull(output.short)
        assertNull(output.detailed)
    }
}
