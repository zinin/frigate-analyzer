package ru.zinin.frigate.analyzer.core.video

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmpegProcessRunnerTest {
    private val runner = FfmpegProcessRunner()

    @Test
    fun `returns merged stdout and stderr lines on success`() =
        runTest {
            val output =
                runner.run(
                    listOf("/bin/sh", "-c", "echo first; echo second >&2; echo third"),
                    Duration.ofSeconds(10),
                )

            assertEquals(listOf("first", "second", "third"), output)
        }

    @Test
    fun `throws with the output tail when the exit code is not zero`() =
        runTest {
            val exception =
                assertThrows<RuntimeException> {
                    runner.run(listOf("/bin/sh", "-c", "echo boom; exit 3"), Duration.ofSeconds(10))
                }

            assertTrue(exception.message!!.contains("sh exited with code 3"), exception.message)
            assertTrue(exception.message!!.contains("boom"), exception.message)
        }

    @Test
    fun `kills the process and throws when the timeout expires`() =
        runTest {
            val startedAt = System.nanoTime()

            val exception =
                assertThrows<RuntimeException> {
                    runner.run(listOf("/bin/sh", "-c", "exec sleep 30"), Duration.ofMillis(500))
                }

            val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
            assertTrue(exception.message!!.contains("sh timed out after 500 ms"), exception.message)
            assertTrue(elapsed < Duration.ofSeconds(10), "runner waited $elapsed instead of killing the process")
        }

    @Test
    fun `rejects an empty command`() =
        runTest {
            assertThrows<IllegalArgumentException> { runner.run(emptyList(), Duration.ofSeconds(1)) }
        }

    @Test
    fun `rejects a blank executable`() =
        runTest {
            assertThrows<IllegalArgumentException> { runner.run(listOf(" "), Duration.ofSeconds(1)) }
        }

    @Test
    fun `keeps the last lines when the output is longer than the buffer`() =
        runTest {
            val output = runner.run(listOf("/bin/sh", "-c", COUNT_TO_600), Duration.ofSeconds(10))

            assertEquals(500, output.size)
            assertEquals("101", output.first())
            assertEquals("600", output.last())
        }

    @Test
    fun `reports the real tail of a long output when the exit code is not zero`() =
        runTest {
            val exception =
                assertThrows<RuntimeException> {
                    runner.run(
                        listOf("/bin/sh", "-c", "$COUNT_TO_600; echo the-real-reason >&2; exit 4"),
                        Duration.ofSeconds(10),
                    )
                }

            val message = exception.message!!
            assertTrue(message.contains("sh exited with code 4"), message)
            assertTrue(message.endsWith("the-real-reason"), message)
            assertFalse(message.contains(": 1\n"), "the tail must not start at the first line: $message")
        }

    companion object {
        private const val COUNT_TO_600 = "i=1; while [ \$i -le 600 ]; do echo \$i; i=\$((i+1)); done"
    }
}
