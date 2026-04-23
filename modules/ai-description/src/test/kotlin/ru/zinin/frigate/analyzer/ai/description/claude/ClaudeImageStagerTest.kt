package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeImageStagerTest {
    private val tempWriter = mockk<TempFileWriter>()
    private val stager = ClaudeImageStager(tempWriter)

    @Test
    fun `creates one temp file per frame in frameIndex order`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val request =
                DescriptionRequest(
                    recordingId = recordingId,
                    frames =
                        listOf(
                            DescriptionRequest.FrameImage(2, byteArrayOf(1, 2)),
                            DescriptionRequest.FrameImage(0, byteArrayOf(3, 4)),
                            DescriptionRequest.FrameImage(1, byteArrayOf(5, 6)),
                        ),
                    language = "en",
                    shortMaxLength = 200,
                    detailedMaxLength = 1500,
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
            // prefixes must be ordered by frameIndex ascending
            assertEquals(3, prefixes.size)
            assertEquals(listOf(0, 1, 2), prefixes.map { it.substringAfterLast("-frame-").toInt() })
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
