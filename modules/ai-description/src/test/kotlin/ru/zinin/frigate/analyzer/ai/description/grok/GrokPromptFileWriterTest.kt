package ru.zinin.frigate.analyzer.ai.description.grok

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.core.VisionInstructions
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrokPromptFileWriterTest {
    private val tempWriter = mockk<TempFileWriter>()
    private val mapper = TestObjectMappers.internalMapper()
    private val writer = GrokPromptFileWriter(tempWriter, mapper)

    private val recordingId = UUID.randomUUID()
    private val instructions = VisionInstructions(systemPrompt = "sys", preamble = "INTRO", epilogue = "RULES", jsonSchema = null)
    private val request =
        VisionRequest(
            requestId = recordingId,
            frames =
                listOf(
                    DescriptionRequest.FrameImage(2, byteArrayOf(1, 2)),
                    DescriptionRequest.FrameImage(0, byteArrayOf(3, 4)),
                ),
            instructions = instructions,
        )

    @Test
    fun `blocks are preamble with frames header, label+image per frame in frameIndex order, epilogue`() {
        val blocks = writer.buildBlocks(request)
        assertEquals(6, blocks.size)
        assertEquals(mapOf("type" to "text", "text" to "INTRO\n\nFrames (in chronological order):"), blocks[0])
        assertEquals(mapOf("type" to "text", "text" to "Frame 0:"), blocks[1])
        assertEquals("image", blocks[2]["type"])
        assertEquals("image/jpeg", blocks[2]["mimeType"])
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(3, 4)), blocks[2]["data"])
        assertEquals(mapOf("type" to "text", "text" to "Frame 2:"), blocks[3])
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2)), blocks[4]["data"])
        assertEquals(mapOf("type" to "text", "text" to "RULES"), blocks[5])
    }

    @Test
    fun `write stores a json file whose content parses back to the blocks`() =
        runTest {
            val prefix = slot<String>()
            val suffix = slot<String>()
            val bytes = slot<ByteArray>()
            coEvery { tempWriter.createTempFile(capture(prefix), capture(suffix), capture(bytes)) } returns
                Path.of("/tmp/prompt.json")

            val path = writer.write(request)

            assertEquals(Path.of("/tmp/prompt.json"), path)
            assertEquals("grok-$recordingId", prefix.captured)
            assertEquals(".json", suffix.captured)
            val parsed = mapper.readTree(bytes.captured)
            assertTrue(parsed.isArray)
            assertEquals(6, parsed.size())
            assertEquals("image", parsed[2]["type"].asText())
        }

    @Test
    fun `delete removes the file through the temp writer`() =
        runTest {
            coEvery { tempWriter.deleteFiles(listOf(Path.of("/tmp/prompt.json"))) } returns 1

            writer.delete(Path.of("/tmp/prompt.json"))

            coVerify(exactly = 1) { tempWriter.deleteFiles(listOf(Path.of("/tmp/prompt.json"))) }
        }

    @Test
    fun `delete swallows temp writer failures`() =
        runTest {
            coEvery { tempWriter.deleteFiles(any()) } throws IllegalStateException("disk gone")

            writer.delete(Path.of("/tmp/prompt.json"))
        }
}
