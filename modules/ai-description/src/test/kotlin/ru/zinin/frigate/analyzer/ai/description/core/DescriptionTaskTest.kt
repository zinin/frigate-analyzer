package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DescriptionTaskTest {
    private fun request(language: String = "en") =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = language,
            shortMaxLength = 150,
            detailedMaxLength = 800,
        )

    @Test
    fun `preamble names the language`() {
        assertTrue(DescriptionTask.instructions(request("ru")).preamble.contains("Write both descriptions in Russian."))
        assertTrue(DescriptionTask.instructions(request("en")).preamble.contains("Write both descriptions in English."))
    }

    @Test
    fun `epilogue carries the JSON shape and the numeric limits`() {
        val epilogue = DescriptionTask.instructions(request()).epilogue
        assertTrue(epilogue.contains("""{"short": "...", "detailed": "..."}"""))
        assertTrue(epilogue.contains("must not exceed 150 characters"))
        assertTrue(epilogue.contains("must not exceed 800 characters"))
    }

    @Test
    fun `system prompt and schema are the fixed description ones`() {
        val instructions = DescriptionTask.instructions(request())
        assertEquals(DescriptionTask.SYSTEM_PROMPT, instructions.systemPrompt)
        assertEquals(DescriptionTask.JSON_SCHEMA, instructions.jsonSchema)
    }

    @Test
    fun `rejects unknown language code`() {
        assertFailsWith<IllegalStateException> { DescriptionTask.instructions(request("de")) }
    }
}
