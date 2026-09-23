package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /** Одного ограничения длины мало: модель считает его потолком и пишет подпись, а не описание. */
    @Test
    fun `the short description asks for what happened, movement included`() {
        val epilogue = DescriptionTask.instructions(request()).epilogue

        assertTrue(epilogue.contains("\"short\": one to three sentences on what happened, including any movement"))
        assertTrue(epilogue.contains("must not exceed 150 characters"))
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

    /**
     * Правило про инструменты принадлежит провайдеру, а не задаче: Claude получает кадры ссылками
     * `@path` и видит их только вызовом Read, а Grok — готовыми image-блоками. Общий запрет
     * инструментов запрещал Claude единственный способ увидеть кадр, и модель отвечала либо прозой
     * (InvalidResponse), либо валидным JSON про недоступное изображение — вторая форма уходила
     * получателям вместо описания и не оставляла в логах ничего.
     */
    @Test
    fun `the shared system prompt leaves the tool rule to the provider`() {
        val systemPrompt = DescriptionTask.instructions(request()).systemPrompt

        assertFalse(systemPrompt.contains("tool", ignoreCase = true))
        assertTrue(systemPrompt.contains("Answer only with the requested JSON object."))
    }

    @Test
    fun `frames go in time order, captioned with their time when it is known`() {
        val request =
            request().copy(
                frames =
                    listOf(
                        DescriptionRequest.FrameImage(3, byteArrayOf(3), offsetSeconds = 7.5),
                        DescriptionRequest.FrameImage(1, byteArrayOf(1), offsetSeconds = 2.0),
                        DescriptionRequest.FrameImage(2, byteArrayOf(2)),
                    ),
            )

        val images = DescriptionTask.images(request)

        assertEquals(listOf("Frame at 2.0s", "Frame 2", "Frame at 7.5s"), images.map { it.caption })
        assertEquals(listOf<Byte>(1, 2, 3), images.map { it.bytes.single() })
    }

    @Test
    fun `frames alone are introduced by the chronological header`() {
        assertEquals("Frames (in chronological order):", DescriptionTask.instructions(request()).imagesHeader)
    }

    @Test
    fun `the vision request carries the recording id, the images and the instructions`() {
        val request = request()

        val vision = DescriptionTask.visionRequest(request)

        assertEquals(request.recordingId, vision.requestId)
        assertEquals(DescriptionTask.images(request), vision.images)
        assertEquals(DescriptionTask.instructions(request), vision.instructions)
    }
}
