package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.core.VisionImage
import ru.zinin.frigate.analyzer.ai.description.core.VisionInstructions
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeImageStagerTest {
    private val tempWriter = mockk<TempFileWriter>()
    private val stager = ClaudeImageStager(tempWriter)

    @Test
    fun `creates one temp file per image in the order of the request`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val request =
                VisionRequest(
                    requestId = recordingId,
                    images =
                        listOf(
                            VisionImage(byteArrayOf(1, 2), "Storyboard"),
                            VisionImage(byteArrayOf(3, 4), "Frame at 1.0s"),
                            VisionImage(byteArrayOf(5, 6), "Frame at 2.0s"),
                        ),
                    instructions = VisionInstructions("sys", "pre", "HEADER:", "epi", jsonSchema = null),
                )

            val prefixes = mutableListOf<String>()
            val bytes = mutableListOf<ByteArray>()
            coEvery {
                tempWriter.createTempFile(capture(prefixes), any(), capture(bytes))
            } answers {
                Path.of("/tmp/${firstArg<String>()}-stub.jpg")
            }

            val paths = stager.stage(request)

            assertEquals(3, paths.size)
            assertEquals(listOf(0, 1, 2), prefixes.map { it.substringAfterLast("-image-").toInt() })
            assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4), listOf<Byte>(5, 6)), bytes.map { it.toList() })
        }

    @Test
    fun `cleanup delegates to deleteFiles with the same paths`() =
        runTest {
            val paths: List<Path> = listOf(Path.of("/tmp/a.jpg"), Path.of("/tmp/b.jpg"))
            val captured = slot<List<Path>>()
            coEvery { tempWriter.deleteFiles(capture(captured)) } returns paths.size

            stager.cleanup(paths)

            coVerify(exactly = 1) { tempWriter.deleteFiles(any()) }
            assertEquals(paths, captured.captured)
        }
}
