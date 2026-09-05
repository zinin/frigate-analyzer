package ru.zinin.frigate.analyzer.ai.description.grok

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionTask
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrokBackendTest {
    @TempDir
    lateinit var tempDir: Path

    private val promptFile = Path.of("/tmp/frigate-analyzer/prompt.json")
    private val promptFileWriter = mockk<GrokPromptFileWriter>(relaxUnitFun = true)
    private val descriptionRequest =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, byteArrayOf(1))),
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
        )
    private val budget: Duration = Duration.ofSeconds(90)
    private val request =
        VisionRequest(
            requestId = descriptionRequest.recordingId,
            frames = descriptionRequest.frames,
            instructions = DescriptionTask.instructions(descriptionRequest),
        )

    private fun props() =
        GrokProperties(
            cliPath = tempDir.resolve("missing-grok").toString(),
            model = "grok-4.6",
            effort = "low",
            home = tempDir.resolve("home").toString(),
            workingDirectory = tempDir.resolve("cwd").toString(),
            proxy = GrokProperties.ProxySection("", "", ""),
        )

    private fun backend(
        runner: GrokProcessRunner,
        properties: GrokProperties = props(),
    ): GrokBackend {
        coEvery { promptFileWriter.write(any()) } returns promptFile
        return GrokBackend(
            model = properties.model,
            effort = properties.effort,
            authScopeId = "grok:${properties.model}",
            promptFileWriter = promptFileWriter,
            commandBuilder = GrokCommandBuilder(properties),
            runner = runner,
            outputParser = GrokOutputParser(TestObjectMappers.internalMapper()),
            exceptionMapper = GrokExceptionMapper(),
            guard = GrokHomeGuard(),
        )
    }

    private fun result(
        exitCode: Int,
        stdout: String,
        stderr: String = "",
    ) = GrokProcessResult(exitCode, stdout, stderr)

    @Test
    fun `success returns normalized structured output and deletes the prompt file`() =
        runTest {
            val stdout = """{"stopReason":"end_turn","sessionId":"s","structuredOutput":{"short":"Car","detailed":"A car."}}"""
            val backend = backend(GrokProcessRunner { result(0, stdout) })

            assertEquals("""{"short":"Car","detailed":"A car."}""", backend.complete(request, budget).primary)
            coVerify(exactly = 1) { promptFileWriter.delete(promptFile) }
        }

    @Test
    fun `both representations of the answer reach the executor`() =
        runTest {
            val stdout =
                """{"stopReason":"end_turn","structuredOutput":{"short":"Bike"},""" +
                    """"text":"{\"short\":\"Bike\",\"detailed\":\"A bike.\"}"}"""
            val backend = backend(GrokProcessRunner { result(0, stdout) })

            val response = backend.complete(request, budget)

            assertEquals("""{"short":"Bike"}""", response.primary)
            assertEquals("""{"short":"Bike","detailed":"A bike."}""", response.fallback)
        }

    @Test
    fun `runner receives the command built for the prompt file`() =
        runTest {
            var seen: GrokCommand? = null
            val backend =
                backend(
                    GrokProcessRunner {
                        seen = it
                        result(0, """{"stopReason":"end_turn","structuredOutput":{"short":"a","detailed":"b"}}""")
                    },
                )
            backend.complete(request, budget)
            assertTrue(seen!!.argv.contains(promptFile.toString()))
            assertEquals(tempDir.resolve("home").toString(), seen!!.environment["GROK_HOME"])
        }

    @Test
    fun `the configured model and effort reach the command`() =
        runTest {
            var seen: GrokCommand? = null
            val backend =
                backend(
                    GrokProcessRunner {
                        seen = it
                        result(0, """{"stopReason":"end_turn","structuredOutput":{"short":"a","detailed":"b"}}""")
                    },
                )
            backend.complete(request, budget)
            val argv = seen!!.argv
            assertEquals("grok-4.6", argv[argv.indexOf("-m") + 1])
            assertEquals("low", argv[argv.indexOf("--effort") + 1])
        }

    @Test
    fun `auth error envelope is Unauthorized and still deletes the prompt file`() =
        runTest {
            val stdout =
                """{"type":"error","message":"Not signed in. To authenticate without a browser, """ +
                    """run:\n  grok login --device-code"}"""
            val backend = backend(GrokProcessRunner { result(1, stdout, "Error: Not signed in") })

            val e = assertFailsWith<DescriptionException.Unauthorized> { backend.complete(request, budget) }
            assertTrue(e.detail.startsWith("Not signed in"))
            coVerify(exactly = 1) { promptFileWriter.delete(promptFile) }
        }

    @Test
    fun `error envelope on exit 0 is still Unauthorized and deletes the prompt file`() =
        runTest {
            val stdout =
                """{"type":"error","message":"Not signed in. To authenticate without a browser, """ +
                    """run:\n  grok login --device-code"}"""
            val backend = backend(GrokProcessRunner { result(0, stdout) })

            val e = assertFailsWith<DescriptionException.Unauthorized> { backend.complete(request, budget) }
            assertTrue(e.detail.startsWith("Not signed in"))
            coVerify(exactly = 1) { promptFileWriter.delete(promptFile) }
        }

    @Test
    fun `schema rejection is retried without the flag and the answer is read from the text`() =
        runTest {
            val commands = mutableListOf<GrokCommand>()
            val schemaError =
                """{"type":"error","message":"API error (status 400 Bad Request): This response_format type is unavailable now"}"""
            val textAnswer =
                """{"stopReason":"end_turn","text":"{\"short\":\"Bike\",\"detailed\":\"A bike.\"}"}"""
            val backend =
                backend(
                    GrokProcessRunner { command ->
                        commands += command
                        if (command.argv.contains("--json-schema")) result(1, schemaError) else result(0, textAnswer)
                    },
                )

            assertEquals("""{"short":"Bike","detailed":"A bike."}""", backend.complete(request, budget).primary)
            assertEquals(2, commands.size)
            assertTrue(commands[0].argv.contains("--json-schema"))
            assertFalse(commands[1].argv.contains("--json-schema"))
            coVerify(exactly = 1) { promptFileWriter.delete(promptFile) }
        }

    @Test
    fun `schema rejection is remembered so later calls skip the flag`() =
        runTest {
            val commands = mutableListOf<GrokCommand>()
            val schemaError =
                """{"type":"error","message":"litellm.BadRequestError: failed to parse grammar. Received Model Group=DKS-Vision"}"""
            val textAnswer =
                """{"stopReason":"end_turn","text":"{\"short\":\"Bike\",\"detailed\":\"A bike.\"}"}"""
            val backend =
                backend(
                    GrokProcessRunner { command ->
                        commands += command
                        if (command.argv.contains("--json-schema")) result(1, schemaError) else result(0, textAnswer)
                    },
                )

            backend.complete(request, budget)
            backend.complete(request, budget)

            assertEquals(3, commands.size)
            assertFalse(commands[2].argv.contains("--json-schema"))
        }

    @Test
    fun `a schema rejection that repeats without the flag is reported as Transport`() =
        runTest {
            val schemaError =
                """{"type":"error","message":"This response_format type is unavailable now"}"""
            val backend = backend(GrokProcessRunner { result(1, schemaError) })

            assertFailsWith<DescriptionException.Transport> { backend.complete(request, budget) }
        }

    @Test
    fun `other non-zero exit is Transport`() =
        runTest {
            val backend = backend(GrokProcessRunner { result(1, "", "connection reset") })
            assertFailsWith<DescriptionException.Transport> { backend.complete(request, budget) }
        }

    @Test
    fun `missing structured output with max_tokens is InvalidResponse`() =
        runTest {
            val backend = backend(GrokProcessRunner { result(0, """{"stopReason":"max_tokens"}""") })
            assertFailsWith<DescriptionException.InvalidResponse> { backend.complete(request, budget) }
        }

    @Test
    fun `runner failure still deletes the prompt file`() =
        runTest {
            val backend = backend(GrokProcessRunner { throw DescriptionException.Transport(detail = "cannot start") })
            assertFailsWith<DescriptionException.Transport> { backend.complete(request, budget) }
            coVerify(exactly = 1) { promptFileWriter.delete(promptFile) }
        }

    @Test
    fun `identifies itself as grok with a device-code hint`() {
        val backend = backend(GrokProcessRunner { result(0, "{}") })
        assertEquals("grok", backend.providerId)
        assertTrue(backend.authRecoveryHint.contains("grok login --device-code"))
    }
}
