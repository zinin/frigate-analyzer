package ru.zinin.frigate.analyzer.ai.description.claude

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import ru.zinin.frigate.analyzer.ai.description.core.JudgeTask
import ru.zinin.frigate.analyzer.ai.description.core.VisionImage
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
    private val instructions =
        VisionInstructions(
            systemPrompt = "sys",
            preamble = "PREAMBLE\n",
            imagesHeader = "HEADER:",
            epilogue = "EPILOGUE\n",
            jsonSchema = null,
        )

    private fun request(images: List<VisionImage>) = VisionRequest(UUID.randomUUID(), images, instructions)

    private val paths = listOf(Path.of("/tmp/a/image-0.jpg"), Path.of("/tmp/a/image-1.jpg"))

    @Test
    fun `assembles preamble, header, captioned image references and epilogue in that order`() {
        val prompt =
            builder.build(
                request(listOf(VisionImage(ByteArray(1), "Storyboard"), VisionImage(ByteArray(1), "Frame at 5.0s"))),
                paths,
            )
        val expected =
            "PREAMBLE\n\nHEADER:\n- Storyboard: @/tmp/a/image-0.jpg\n- Frame at 5.0s: @/tmp/a/image-1.jpg\n\nEPILOGUE"
        assertEquals(expected, prompt)
    }

    @Test
    fun `keeps the order the task chose`() {
        val prompt = builder.build(request(listOf(VisionImage(ByteArray(1), "B"), VisionImage(ByteArray(1), "A"))), paths)

        assertTrue(prompt.indexOf("- B: @/tmp/a/image-0.jpg") < prompt.indexOf("- A: @/tmp/a/image-1.jpg"))
    }

    /**
     * Судья ходит через этот же билдер. Пока подписи и заголовок писал провайдер, промпт судьи
     * выглядел именно так; раскадровка описаний не должна сдвинуть в нём ни символа.
     */
    @Test
    fun `the judge prompt keeps its shape`() {
        val judge =
            JudgeRequest(
                recordingId = UUID.randomUUID(),
                camId = "cam2",
                frames = listOf(DescriptionRequest.FrameImage(1, ByteArray(1)), DescriptionRequest.FrameImage(0, ByteArray(1))),
                contextJson = "{}",
                language = "en",
                maxSnoozeMinutes = 30,
            )
        val vision = JudgeTask.visionRequest(judge)

        val prompt = builder.build(vision, listOf(Path.of("/tmp/a/frame-0.jpg"), Path.of("/tmp/a/frame-1.jpg")))

        val expected =
            vision.instructions.preamble.trimEnd() +
                "\n\nFrames (in chronological order):\n- Frame 0: @/tmp/a/frame-0.jpg\n- Frame 1: @/tmp/a/frame-1.jpg\n\n" +
                vision.instructions.epilogue.trimEnd()
        assertEquals(expected, prompt)
    }

    @Test
    fun `path count must match image count`() {
        assertFailsWith<IllegalArgumentException> {
            builder.build(request(listOf(VisionImage(ByteArray(1), "Frame 0"))), paths)
        }
    }
}
