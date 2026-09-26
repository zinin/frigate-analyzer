package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JudgeTaskTest {
    private val request =
        JudgeRequest(
            recordingId = UUID.randomUUID(),
            camId = "cam2",
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            contextJson = """{"recording":{"cam":"cam2"}}""",
            language = "ru",
            maxSnoozeMinutes = 30,
        )

    @Test
    fun `preamble names the camera and epilogue carries context, policy, snooze ceiling and language`() {
        val instructions = JudgeTask.instructions(request)
        assertTrue(instructions.preamble.contains("camera `cam2`"))
        assertTrue(instructions.epilogue.contains("""{"recording":{"cam":"cam2"}}"""))
        assertTrue(instructions.epilogue.contains("FALSE_POSITIVE"))
        assertTrue(instructions.epilogue.contains("When in doubt about a person, PUBLISH"))
        assertTrue(instructions.epilogue.contains("`snooze_minutes` (0–30)"))
        assertTrue(instructions.epilogue.contains("one sentence in Russian"))
        assertEquals(JudgeTask.SYSTEM_PROMPT, instructions.systemPrompt)
        assertEquals(JudgeTask.JSON_SCHEMA, instructions.jsonSchema)
    }

    /** Тот же довод, что в `DescriptionTaskTest`: судья ходит через тех же провайдеров. */
    @Test
    fun `the shared system prompt leaves the tool rule to the provider`() {
        val systemPrompt = JudgeTask.instructions(request).systemPrompt

        assertFalse(systemPrompt.contains("tool", ignoreCase = true))
        assertTrue(systemPrompt.contains("Answer only with the requested JSON object."))
    }

    @Test
    fun `is deterministic for the same input`() {
        assertEquals(JudgeTask.instructions(request), JudgeTask.instructions(request))
    }

    /**
     * Пока подписи писали провайдеры, судья видел ровно это; раскадровка описаний не должна это сдвинуть.
     * Время кадра, даже если оно есть, в подписи судьи не попадает — его промпт не меняется ни на символ.
     */
    @Test
    fun `frames are captioned Frame N in frameIndex order under the chronological header`() {
        val judge =
            request.copy(
                frames =
                    listOf(
                        DescriptionRequest.FrameImage(2, byteArrayOf(2), offsetSeconds = 5.0),
                        DescriptionRequest.FrameImage(0, byteArrayOf(0)),
                    ),
            )

        val vision = JudgeTask.visionRequest(judge)

        assertEquals(listOf("Frame 0", "Frame 2"), vision.images.map { it.caption })
        assertEquals(listOf<Byte>(0, 2), vision.images.map { it.bytes.single() })
        assertEquals("Frames (in chronological order):", vision.instructions.imagesHeader)
    }
}
