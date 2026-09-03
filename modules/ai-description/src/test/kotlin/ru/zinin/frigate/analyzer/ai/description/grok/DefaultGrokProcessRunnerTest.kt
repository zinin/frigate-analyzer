package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledOnOs(OS.LINUX, OS.MAC)
class DefaultGrokProcessRunnerTest {
    @TempDir
    lateinit var tempDir: Path

    private val runner = DefaultGrokProcessRunner()

    private fun stub(script: String): Path {
        val file = tempDir.resolve("grok")
        file.writeText("#!/bin/sh\n$script\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
        return file
    }

    private fun command(
        binary: Path,
        env: Map<String, String> = emptyMap(),
    ) = GrokCommand(argv = listOf(binary.toString()), environment = env, workingDirectory = tempDir)

    @Test
    fun `captures stdout and exit code 0`() =
        runBlocking {
            val result = runner.run(command(stub("""printf '%s' '{"text":"ok"}'""")))

            assertEquals(0, result.exitCode)
            assertEquals("""{"text":"ok"}""", result.stdout)
        }

    @Test
    fun `captures non-zero exit code and stderr tail`() =
        runBlocking {
            val result = runner.run(command(stub("""echo 'Error: boom' >&2; printf '%s' '{"type":"error","message":"boom"}'; exit 1""")))

            assertEquals(1, result.exitCode)
            assertEquals("""{"type":"error","message":"boom"}""", result.stdout)
            assertTrue(result.stderrTail.contains("Error: boom"))
        }

    @Test
    fun `passes environment and working directory to the child`() =
        runBlocking {
            val result =
                runner.run(
                    command(
                        stub("""printf '%s|%s' "${'$'}GROK_HOME" "${'$'}(pwd -P)""""),
                        env = mapOf("GROK_HOME" to "/data/home"),
                    ),
                )

            assertEquals("/data/home|${tempDir.toRealPath()}", result.stdout)
        }

    @Test
    fun `stderr is trimmed to the tail`() =
        runBlocking {
            val result = runner.run(command(stub("""head -c 20000 /dev/zero | tr '\0' 'x' >&2; echo END >&2""")))

            assertTrue(result.stderrTail.length <= DefaultGrokProcessRunner.STDERR_TAIL_BYTES)
            assertTrue(result.stderrTail.endsWith("END\n"))
        }

    @Test
    fun `cancellation kills the child process`() =
        runBlocking {
            val pidFile = tempDir.resolve("pid")
            // exec заменяет sh на sleep: PID в файле и есть процесс, который должен умереть.
            val binary = stub("""echo $$ > "$pidFile"; exec sleep 30""")

            val job = launch { runner.run(command(binary)) }
            while (!Files.exists(pidFile)) delay(20)
            delay(100)
            val pid = pidFile.readText().trim().toLong()
            assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "child must be alive before cancel")

            job.cancelAndJoin()

            delay(200)
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "child must be dead after cancel")
        }

    @Test
    fun `stdout above the cap is Transport without loading the rest`() {
        val payload = ByteArray(32) { 'x'.code.toByte() }
        assertFailsWith<DescriptionException.Transport> {
            DefaultGrokProcessRunner.readAtMost(ByteArrayInputStream(payload), maxBytes = 16)
        }
        assertEquals("ok", DefaultGrokProcessRunner.readAtMost(ByteArrayInputStream("ok".toByteArray()), maxBytes = 16))
    }

    @Test
    fun `stderr ring keeps the tail`() {
        val payload = ("a".repeat(20) + "END").toByteArray()
        val tail = DefaultGrokProcessRunner.readTail(ByteArrayInputStream(payload), maxBytes = 8)
        assertEquals(8, tail.length)
        assertTrue(tail.endsWith("END"))
    }

    @Test
    fun `isolated environment keeps PATH and overrides, not arbitrary JVM keys`() {
        val env = DefaultGrokProcessRunner.isolatedEnvironment(mapOf("GROK_HOME" to "/data/home"))
        assertEquals("/data/home", env["GROK_HOME"])
        assertTrue(
            env.keys.all { key ->
                key in setOf("PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "USER", "LOGNAME", "TERM") ||
                    key.startsWith("GROK_") ||
                    key.startsWith("XAI_")
            },
        )
        assertFalse("TELEGRAM_BOT_TOKEN" in env)
        assertFalse("DB_PASS" in env)
    }

    @Test
    fun `missing binary is Transport`() {
        runBlocking {
            assertFailsWith<DescriptionException.Transport> {
                runner.run(command(tempDir.resolve("does-not-exist")))
            }
        }
    }

    @Test
    fun `a grandchild holding the pipe open cannot hang the call`() =
        runBlocking {
            // `grok` вышел, но внук унаследовал конец pipe: блокирующее чтение отмену не замечает,
            // и без верхней границы вызов держал бы слот семафора агента до перезапуска JVM.
            // Порядок в стабе важен: JDK при выходе процесса дочитывает доступные байты и закрывает
            // поток сам, поэтому висит только читатель, уже вошедший в блокирующий read, — вывод
            // идёт первым, внук форкается после, и лишняя секунда даёт читателю дойти до блокировки.
            val e =
                assertFailsWith<DescriptionException.Transport> {
                    DefaultGrokProcessRunner(drainTimeoutMs = 300).run(
                        command(stub("""printf '%s' '{"text":"ok"}'; (sleep 3) & sleep 1; exit 0""")),
                    )
                }

            assertTrue(e.message!!.contains("left its output pipe open"))
        }
}
