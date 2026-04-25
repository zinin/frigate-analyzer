package ru.zinin.frigate.analyzer.core.config

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TempFileWriterAdapterTest {
    private val tempFileHelper = mockk<TempFileHelper>()
    private val writer = TempFileWriterAdapter().tempFileWriter(tempFileHelper)

    @Test
    fun `createTempFile delegates to TempFileHelper`() =
        runTest {
            val expected = Path.of("/tmp/test.jpg")
            coEvery { tempFileHelper.createTempFile("pref", ".jpg", any<ByteArray>()) } returns expected

            val result = writer.createTempFile("pref", ".jpg", ByteArray(1))

            assertEquals(expected, result)
            coVerify(exactly = 1) { tempFileHelper.createTempFile("pref", ".jpg", any<ByteArray>()) }
        }

    @Test
    fun `deleteFiles delegates to TempFileHelper and returns count`() =
        runTest {
            val paths = listOf(Path.of("/tmp/a"), Path.of("/tmp/b"))
            coEvery { tempFileHelper.deleteFiles(paths) } returns 2

            val count = writer.deleteFiles(paths)

            assertEquals(2, count)
            coVerify(exactly = 1) { tempFileHelper.deleteFiles(paths) }
        }
}
