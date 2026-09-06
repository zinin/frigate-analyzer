package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `is deterministic for the same input`() {
        assertEquals(JudgeTask.instructions(request), JudgeTask.instructions(request))
    }
}
