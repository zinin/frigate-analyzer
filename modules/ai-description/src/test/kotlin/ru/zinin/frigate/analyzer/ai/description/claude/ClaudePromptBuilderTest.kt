package ru.zinin.frigate.analyzer.ai.description.claude

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.core.VisionInstructions
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudePromptBuilderTest {
    private val builder = ClaudePromptBuilder()
    private val instructions = VisionInstructions(systemPrompt = "sys", preamble = "PREAMBLE\n", epilogue = "EPILOGUE\n", jsonSchema = null)

    private fun request(frames: List<DescriptionRequest.FrameImage>) = VisionRequest(UUID.randomUUID(), frames, instructions)

    private val paths = listOf(Path.of("/tmp/a/frame-0.jpg"), Path.of("/tmp/a/frame-1.jpg"))

    @Test
    fun `assembles preamble, frame references and epilogue in that order`() {
        val prompt =
            builder.build(
                request(listOf(DescriptionRequest.FrameImage(0, ByteArray(1)), DescriptionRequest.FrameImage(1, ByteArray(1)))),
                paths,
            )
        val expected =
            "PREAMBLE\n\nFrames (in chronological order):\n- Frame 0: @/tmp/a/frame-0.jpg\n- Frame 1: @/tmp/a/frame-1.jpg\n\nEPILOGUE"
        assertEquals(expected, prompt)
    }

    @Test
    fun `sorts unordered frames by frameIndex before zipping with the staged paths`() {
        val prompt =
            builder.build(
                request(listOf(DescriptionRequest.FrameImage(1, ByteArray(1)), DescriptionRequest.FrameImage(0, ByteArray(1)))),
                paths,
            )
        assertTrue(prompt.indexOf("Frame 0: @/tmp/a/frame-0.jpg") < prompt.indexOf("Frame 1: @/tmp/a/frame-1.jpg"))
    }

    @Test
    fun `path count must match frame count`() {
        assertFailsWith<IllegalArgumentException> {
            builder.build(request(listOf(DescriptionRequest.FrameImage(0, ByteArray(1)))), paths)
        }
    }
}
