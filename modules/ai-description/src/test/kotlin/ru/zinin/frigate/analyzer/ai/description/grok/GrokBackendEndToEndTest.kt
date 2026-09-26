package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.VisionImage
import ru.zinin.frigate.analyzer.ai.description.core.VisionInstructions
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.SID
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.stripped
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `GrokBackend` с настоящими runner-ом, командой и детектором: stub-скрипт `grok` пишет строку
 * выброса в `$GROK_HOME/logs/unified.jsonl`, как это делает grok 1.0.41. Тесты на фейках этого не
 * ловят: там путь лога задаёт сам тест, а здесь — env процесса, собранный `GrokCommandBuilder`, и
 * детектор обязан читать тот же каталог.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class GrokBackendEndToEndTest {
    @TempDir
    lateinit var tempDir: Path

    private val tempFileWriter =
        object : TempFileWriter {
            override suspend fun createTempFile(
                prefix: String,
                suffix: String,
                content: ByteArray,
            ): Path = Files.createTempFile(tempDir, prefix, suffix).also { Files.write(it, content) }

            override suspend fun deleteFiles(files: List<Path>): Int = files.count { Files.deleteIfExists(it) }
        }

    @Test
    fun `a strip written by the grok process is caught through the real runner and paths`() =
        runBlocking {
            val stripLine = tempDir.resolve("strip-line.jsonl")
            stripLine.writeText(stripped(SID, 1) + "\n")
            val stdout = """{"stopReason":"end_turn","sessionId":"$SID","structuredOutput":{"short":"a","detailed":"b"}}"""
            val binary = tempDir.resolve("grok")
            binary.writeText(
                """
                #!/bin/sh
                mkdir -p "${'$'}GROK_HOME/logs"
                cat '$stripLine' >> "${'$'}GROK_HOME/logs/unified.jsonl"
                printf '%s' '$stdout'
                """.trimIndent() + "\n",
            )
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"))
            val properties =
                GrokProperties(
                    cliPath = binary.toString(),
                    model = "byok-vision",
                    effort = "",
                    home = tempDir.resolve("home").toString(),
                    workingDirectory = Files.createDirectories(tempDir.resolve("cwd")).toString(),
                    proxy = GrokProperties.ProxySection("", "", ""),
                )
            val mapper = TestObjectMappers.internalMapper()
            val backend =
                GrokBackend(
                    model = properties.model,
                    effort = properties.effort,
                    authScopeId = "grok:${properties.model}",
                    promptFileWriter = GrokPromptFileWriter(tempFileWriter, mapper),
                    commandBuilder = GrokCommandBuilder(properties),
                    runner = DefaultGrokProcessRunner(tempFileWriter),
                    outputParser = GrokOutputParser(mapper),
                    exceptionMapper = GrokExceptionMapper(),
                    guard = GrokHomeGuard(),
                    stripDetector = GrokImageStripDetector(properties, mapper),
                )
            val request =
                VisionRequest(
                    UUID.randomUUID(),
                    listOf(VisionImage(byteArrayOf(1, 2, 3), "Frame 0")),
                    VisionInstructions("sys", "pre", "HEADER:", "epi", null),
                )

            val e =
                assertFailsWith<DescriptionException.InvalidResponse> {
                    backend.complete(request, Duration.ofSeconds(30))
                }

            assertTrue(e.message!!.contains("dropped 1 of 1"), e.message)
        }
}
