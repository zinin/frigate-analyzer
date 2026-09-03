package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend
import ru.zinin.frigate.analyzer.ai.description.core.ResultNormalizer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Одна попытка описания через headless Grok Build: `prompt.json` → процесс → `structuredOutput`.
 * Семафор, таймауты и повторы живут в `DefaultDescriptionAgent`; отмена корутины по таймауту
 * убивает процесс в runner-е, а prompt-файл удаляется в `finally` под NonCancellable.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokBackend(
    private val properties: GrokProperties,
    private val promptFileWriter: GrokPromptFileWriter,
    private val commandBuilder: GrokCommandBuilder,
    private val runner: GrokProcessRunner,
    private val outputParser: GrokOutputParser,
    private val exceptionMapper: GrokExceptionMapper,
    private val guard: GrokHomeGuard,
) : DescriptionBackend {
    override val providerId: String = "grok"
    override val authRecoveryHint: String =
        "grok login --device-code (in Docker: docker compose exec frigate-analyzer grok login --device-code)"

    init {
        val home = properties.homePath
        val cwd = properties.workingDirectoryPath
        try {
            Files.createDirectories(home)
            Files.createDirectories(cwd)
        } catch (e: IOException) {
            throw IllegalStateException("Cannot create Grok directories home=$home working-directory=$cwd: ${e.message}", e)
        }
        if (!Files.isWritable(home)) {
            logger.warn { "Grok home $home is not writable; grok login and token refresh will fail (fix: chown the volume to uid 1000)" }
        }
        if (!cliAvailable()) {
            logger.warn {
                "grok CLI not found (cli-path='${properties.cliPath}', PATH lookup otherwise); " +
                    "all description requests will return fallback"
            }
        }
        if (!Files.isRegularFile(home.resolve("auth.json"))) {
            logger.warn {
                "No auth.json in $home; run `$authRecoveryHint`. Not needed only for BYOK models " +
                    "with their own api_key in config.toml"
            }
        }
    }

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        val promptFile = promptFileWriter.write(request)
        try {
            val command = commandBuilder.build(promptFile)
            val result = guard.shared { runner.run(command) }
            if (result.exitCode != 0) {
                throw exceptionMapper.fromFailure(result.exitCode, outputParser.errorMessage(result.stdout), result.stderrTail)
            }
            val output = outputParser.parse(result.stdout)
            logger.debug {
                "Grok describe for recording ${request.recordingId}: ${output.usageSummary}, " +
                    "stopReason=${output.stopReason}, session=${output.sessionId}"
            }
            if (!output.short.isNullOrBlank() && !output.detailed.isNullOrBlank()) {
                return ResultNormalizer.normalize(output.short, output.detailed, request.shortMaxLength, request.detailedMaxLength)
            }
            throw exceptionMapper.fromStopReason(output.stopReason)
        } finally {
            promptFileWriter.delete(promptFile)
        }
    }

    private fun cliAvailable(): Boolean {
        val cliPath = properties.cliPath
        if (cliPath.isNotBlank()) return Files.isExecutable(Path.of(cliPath))
        return System
            .getenv("PATH")
            ?.split(File.pathSeparator)
            .orEmpty()
            .filter { it.isNotBlank() }
            .any { Files.isExecutable(Path.of(it, "grok")) }
    }
}
