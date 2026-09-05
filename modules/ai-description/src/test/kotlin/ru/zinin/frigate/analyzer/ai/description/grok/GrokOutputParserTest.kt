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
    fun `nested objects in usage do not cost a valid response`() {
        val stdout =
            """
            {"stopReason":"end_turn","usage":{"input_tokens":{"text":10,"image":20},"output_tokens":5},
             "total_cost_usd":{"amount":0.01},
             "structuredOutput":{"short":"Car","detailed":"A car in the yard."}}
            """.trimIndent()

        val output = parser.parse(stdout)

        assertEquals("Car", output.short)
        assertTrue(output.usageSummary.contains("input_tokens=?"))
        assertTrue(output.usageSummary.contains("output_tokens=5"))
        assertTrue(output.usageSummary.contains("total_cost_usd=unknown"))
    }

    @Test
    fun `structured output wins and is not marked as text`() {
        assertEquals(false, parser.parse(success).fromText)
    }

    @Test
    fun `fields are read from the response text when the model ignored the schema`() {
        val stdout =
            """{"text":"{\"short\":\"Bike\",\"detailed\":\"A bike by the fence.\"}","stopReason":"end_turn","sessionId":"s"}"""

        val output = parser.parse(stdout)

        assertEquals("Bike", output.short)
        assertEquals("A bike by the fence.", output.detailed)
        assertTrue(output.fromText)
    }

    @Test
    fun `json fenced in markdown inside the text is still read`() {
        val stdout =
            """{"text":"```json\n{\"short\":\"Bike\",\"detailed\":\"A bike.\"}\n```","stopReason":"end_turn"}"""

        val output = parser.parse(stdout)

        assertEquals("Bike", output.short)
        assertEquals("A bike.", output.detailed)
        assertTrue(output.fromText)
    }

    @Test
    fun `partial structured output is completed from the text`() {
        val stdout =
            """{"text":"{\"short\":\"Bike\",\"detailed\":\"A bike.\"}","structuredOutput":{"short":"Bike"},"stopReason":"end_turn"}"""

        val output = parser.parse(stdout)

        assertEquals("Bike", output.short)
        assertEquals("A bike.", output.detailed)
    }

    @Test
    fun `blank structured fields are completed from the text instead of being kept`() {
        val stdout =
            """{"text":"{\"short\":\"Bike\",\"detailed\":\"A bike.\"}","structuredOutput":{"short":"","detailed":"  "},"stopReason":"end_turn"}"""

        val output = parser.parse(stdout)

        assertEquals("Bike", output.short)
        assertEquals("A bike.", output.detailed)
        assertTrue(output.fromText)
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
