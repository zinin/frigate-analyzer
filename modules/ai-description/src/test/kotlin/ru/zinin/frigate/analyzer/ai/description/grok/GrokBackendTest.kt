package ru.zinin.frigate.analyzer.ai.description.grok

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionTask
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.OTHER
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.SID
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.inferenceDone
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.stripped
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
    private val request = DescriptionTask.visionRequest(descriptionRequest)

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
            stripDetector = GrokImageStripDetector(properties, TestObjectMappers.internalMapper()),
        )
    }

    private fun result(
        exitCode: Int,
        stdout: String,
        stderr: String = "",
    ) = GrokProcessResult(exitCode, stdout, stderr)

    /** Что grok дописал бы в `unified.jsonl` за время запуска: фейк-runner зовёт это из `run`. */
    private fun appendToGrokLog(vararg entries: String) {
        val log = tempDir.resolve("home/logs/unified.jsonl")
        Files.createDirectories(log.parent)
        Files.writeString(log, entries.joinToString("") { "$it\n" }, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun answerOf(sessionId: String) =
        """{"stopReason":"end_turn","sessionId":"$sessionId","structuredOutput":{"short":"Car","detailed":"A car."}}"""

    private suspend fun warningsDuring(block: suspend () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

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

    /**
     * Кадры Grok получает готовыми image-блоками, инструменты ему не нужны и отключены флагами
     * команды. Запрет остаётся в промпте провайдера — общий текст задачи его больше не несёт.
     */
    @Test
    fun `the system prompt handed to grok keeps the tool ban`() =
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
            assertEquals(
                "${DescriptionTask.SYSTEM_PROMPT} ${GrokBackend.TOOL_RULE}",
                argv[argv.indexOf("--system-prompt-override") + 1],
            )
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
    fun `an answer produced after grok dropped the frames is rejected`() =
        runTest {
            val backend =
                backend(
                    GrokProcessRunner {
                        appendToGrokLog(inferenceDone(SID), stripped(SID, 1))
                        result(0, answerOf(SID))
                    },
                )

            val e = assertFailsWith<DescriptionException.InvalidResponse> { backend.complete(request, budget) }

            assertTrue(e.message!!.contains("dropped 1 of 1"), e.message)
            assertTrue(e.message!!.contains("max-image-side"), e.message)
            coVerify(exactly = 1) { promptFileWriter.delete(promptFile) }
        }

    @Test
    fun `a run whose session has no strip returns the answer`() =
        runTest {
            val backend =
                backend(
                    GrokProcessRunner {
                        appendToGrokLog(stripped(OTHER, 10), inferenceDone(SID))
                        result(0, answerOf(SID))
                    },
                )

            assertEquals("""{"short":"Car","detailed":"A car."}""", backend.complete(request, budget).primary)
        }

    @Test
    fun `an unverifiable run returns the answer and warns once per process`() =
        runTest {
            val backend = backend(GrokProcessRunner { result(0, answerOf(SID)) })

            val warnings =
                warningsDuring {
                    backend.complete(request, budget)
                    backend.complete(request, budget)
                }

            assertEquals(1, warnings.count { it.startsWith("Cannot verify frame delivery") }, warnings.toString())
        }

    /** Review Focus 4: ответа нет вовсе, но отчёт обязан назвать выброс, а не стоп-причину. */
    @Test
    fun `a strip is reported even when grok returned no answer`() =
        runTest {
            val backend =
                backend(
                    GrokProcessRunner {
                        appendToGrokLog(stripped(SID, 1))
                        result(0, """{"stopReason":"max_tokens","sessionId":"$SID"}""")
                    },
                )

            val e = assertFailsWith<DescriptionException.InvalidResponse> { backend.complete(request, budget) }

            assertTrue(e.message!!.contains("dropped 1 of 1"), e.message)
        }

    @Test
    fun `an error envelope is classified even when the log holds a strip of the session`() =
        runTest {
            val stdout =
                """{"type":"error","message":"Not signed in. To authenticate without a browser, """ +
                    """run:\n  grok login --device-code"}"""
            val backend =
                backend(
                    GrokProcessRunner {
                        appendToGrokLog(stripped(SID, 1))
                        result(1, stdout, "Error: Not signed in")
                    },
                )

            assertFailsWith<DescriptionException.Unauthorized> { backend.complete(request, budget) }
        }

    @Test
    fun `a non-zero exit is classified even when the log holds a strip of the session`() =
        runTest {
            val backend =
                backend(
                    GrokProcessRunner {
                        appendToGrokLog(stripped(SID, 1))
                        result(1, "", "connection reset")
                    },
                )

            assertFailsWith<DescriptionException.Transport> { backend.complete(request, budget) }
        }

    @Test
    fun `after a schema retry the run whose answer is used is the one inspected`() =
        runTest {
            val schemaError = """{"type":"error","message":"litellm.BadRequestError: failed to parse grammar"}"""
            val textAnswer = """{"stopReason":"end_turn","sessionId":"$SID","text":"{\"short\":\"a\",\"detailed\":\"b\"}"}"""
            val backend =
                backend(
                    GrokProcessRunner { command ->
                        if (command.argv.contains("--json-schema")) {
                            result(1, schemaError)
                        } else {
                            // Записи сессии появляются только во втором запуске: хвост, снятый после
                            // первого, их бы не содержал, и выброс прошёл бы незамеченным.
                            appendToGrokLog(inferenceDone(SID), stripped(SID, 1))
                            result(0, textAnswer)
                        }
                    },
                )

            assertFailsWith<DescriptionException.InvalidResponse> { backend.complete(request, budget) }
        }

    @Test
    fun `a request without frames is not checked for dropped frames`() =
        runTest {
            val backend =
                backend(
                    GrokProcessRunner {
                        appendToGrokLog(stripped(SID, 1))
                        result(0, answerOf(SID))
                    },
                )

            val response = backend.complete(request.copy(images = emptyList()), budget)

            assertEquals("""{"short":"Car","detailed":"A car."}""", response.primary)
        }

    @Test
    fun `identifies itself as grok with a device-code hint`() {
        val backend = backend(GrokProcessRunner { result(0, "{}") })
        assertEquals("grok", backend.providerId)
        assertTrue(backend.authRecoveryHint.contains("grok login --device-code"))
    }
}
