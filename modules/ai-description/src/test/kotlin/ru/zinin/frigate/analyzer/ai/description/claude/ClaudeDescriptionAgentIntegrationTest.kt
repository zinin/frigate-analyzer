package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * Opt-in end-to-end test for [ClaudeDescriptionAgent] against a bash stub CLI.
 *
 * Skipped by default. Run explicitly:
 * ```
 * INTEGRATION_CLAUDE=stub ./gradlew :frigate-analyzer-ai-description:test \
 *     --tests ClaudeDescriptionAgentIntegrationTest
 * ```
 *
 * Verifies: prompt → subprocess → stream-json parse → [ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult],
 * without hitting Anthropic.
 *
 * DISABLED: the SDK (spring-ai-claude-code-sdk, `ClaudeClient.async(...)`) invokes the CLI in
 * bidirectional `--input-format stream-json --output-format stream-json` mode. It:
 *   1. opens the subprocess with a persistent stdin,
 *   2. writes the prompt as a JSON line to stdin,
 *   3. keeps reading stdout until a terminal `result` event arrives.
 *
 * A `cat <<EOF` stub exits before the SDK can write to stdin ("IOException: Stream closed"
 * in the SDK's outbound thread), so the Mono never completes and we hit work-timeout.
 *
 * A faithful stub would need to: read a line from stdin, emit the canned JSONL response,
 * then block until stdin closes. This is non-trivial in pure sh and doesn't really buy us
 * coverage beyond what [ClaudeDescriptionAgentTest] already provides with a fake
 * [ClaudeInvoker]. Leaving the scaffolding here as a starting point if we later need a
 * real CLI compatibility test; flip from `@Disabled` to `@EnabledIfEnvironmentVariable`
 * once the stub properly participates in the streaming protocol.
 */
@Disabled(
    "Stub CLI does not implement the SDK's bidirectional stream-json protocol; " +
        "ClaudeDescriptionAgentTest covers the describe() pipeline via ClaudeInvoker.",
)
@EnabledIfEnvironmentVariable(named = "INTEGRATION_CLAUDE", matches = "stub")
class ClaudeDescriptionAgentIntegrationTest {
    @Test
    fun `end-to-end prompt to parsed result with stub CLI`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val stubClaude = tempDir.resolve("claude")
        stubClaude.writeText(
            """#!/bin/sh
cat <<'JSON'
{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"{\"short\": \"stub-s\", \"detailed\": \"stub-d\"}"}]}}
{"type":"result","subtype":"success","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"result":"ok","session_id":"test","total_cost_usd":0,"usage":{"input_tokens":0,"output_tokens":0}}
JSON
""",
        )
        Files.setPosixFilePermissions(
            stubClaude,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )

        val claudeProps =
            ClaudeProperties(
                oauthToken = "fake",
                model = "opus",
                cliPath = stubClaude.absolutePathString(),
                workingDirectory = tempDir.absolutePathString(),
                proxy = ClaudeProperties.ProxySection("", "", ""),
            )
        val common =
            DescriptionProperties.CommonSection(
                language = "en",
                shortMaxLength = 200,
                detailedMaxLength = 1500,
                maxFrames = 10,
                queueTimeout = Duration.ofSeconds(30),
                timeout = Duration.ofSeconds(30),
                maxConcurrent = 1,
            )
        val descriptionProps = DescriptionProperties(enabled = true, provider = "claude", common = common)

        val factory = ClaudeAsyncClientFactory(claudeProps)
        val invoker = DefaultClaudeInvoker(factory, descriptionProps)

        val mapper = ObjectMapper().registerKotlinModule()
        val tempFileWriter =
            object : TempFileWriter {
                override suspend fun createTempFile(
                    prefix: String,
                    suffix: String,
                    content: ByteArray,
                ): Path {
                    val p = tempDir.resolve("$prefix$suffix")
                    p.parent.createDirectories()
                    Files.write(p, content)
                    return p
                }

                override suspend fun deleteFiles(files: List<Path>): Int = files.count { Files.deleteIfExists(it) }
            }
        val stager = ClaudeImageStager(tempFileWriter)

        val agent =
            ClaudeDescriptionAgent(
                claudeProperties = claudeProps,
                descriptionProperties = descriptionProps,
                promptBuilder = ClaudePromptBuilder(),
                responseParser = ClaudeResponseParser(mapper),
                imageStager = stager,
                invoker = invoker,
                exceptionMapper = ClaudeExceptionMapper(),
            )

        val request =
            DescriptionRequest(
                recordingId = UUID.randomUUID(),
                frames = listOf(DescriptionRequest.FrameImage(0, byteArrayOf(1))),
                language = "en",
                shortMaxLength = 200,
                detailedMaxLength = 1500,
            )

        val result = agent.describe(request)
        assertEquals("stub-s", result.short)
        assertEquals("stub-d", result.detailed)
    }
}
