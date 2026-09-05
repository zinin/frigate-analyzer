package ru.zinin.frigate.analyzer.ai.description.grok

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
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
    private val writer = GrokPromptFileWriter(tempWriter, GrokPromptBuilder(), mapper)

    private val recordingId = UUID.randomUUID()
    private val request =
        DescriptionRequest(
            recordingId = recordingId,
            frames =
                listOf(
                    DescriptionRequest.FrameImage(2, byteArrayOf(1, 2)),
                    DescriptionRequest.FrameImage(0, byteArrayOf(3, 4)),
                ),
            language = "ru",
            shortMaxLength = 150,
            detailedMaxLength = 800,
        )

    @Test
    fun `blocks are intro, label+image per frame in frameIndex order, rules`() {
        val blocks = writer.buildBlocks(request)

        assertEquals(6, blocks.size)
        assertEquals("text", blocks[0]["type"])
        assertTrue(blocks[0]["text"]!!.contains("in Russian"))
        assertEquals(mapOf("type" to "text", "text" to "Frame 0:"), blocks[1])
        assertEquals("image", blocks[2]["type"])
        assertEquals("image/jpeg", blocks[2]["mimeType"])
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(3, 4)), blocks[2]["data"])
        assertEquals(mapOf("type" to "text", "text" to "Frame 2:"), blocks[3])
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2)), blocks[4]["data"])
        assertEquals("text", blocks[5]["type"])
        assertTrue(blocks[5]["text"]!!.contains("150"))
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
