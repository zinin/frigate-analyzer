package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend
import ru.zinin.frigate.analyzer.ai.description.core.ResultNormalizer
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Одна попытка описания через headless Grok Build: `prompt.json` → процесс → `structuredOutput`.
 * Семафор, таймауты и повторы живут в `DefaultDescriptionAgent`; отмена корутины по таймауту
 * убивает процесс в runner-е, а prompt-файл удаляется в `finally` под NonCancellable.
 *
 * Не бин: экземпляр создаёт [GrokBackendFactory] на каждый grok-пресет, поэтому [model], [effort]
 * и [authScopeId] приходят из пресета, а осмотр окружения остаётся в фабрике — один раз на провайдер.
 */
class GrokBackend(
    val model: String,
    val effort: String,
    override val authScopeId: String,
    private val promptFileWriter: GrokPromptFileWriter,
    private val commandBuilder: GrokCommandBuilder,
    private val runner: GrokProcessRunner,
    private val outputParser: GrokOutputParser,
    private val exceptionMapper: GrokExceptionMapper,
    private val guard: GrokHomeGuard,
) : DescriptionBackend {
    override val providerId: String = PROVIDER_ID
    override val authRecoveryHint: String = AUTH_RECOVERY_HINT

    /**
     * Схема поддержана, пока эндпоинт не доказал обратное. Первый отказ по `response_format`/grammar
     * переводит на текстовый JSON до конца жизни процесса. Поле экземпляра, то есть флаг живёт на
     * пресет — ровно правильная область: модель зафиксирована пресетом, и второй пресет с другой
     * моделью не должен наследовать чужой отказ.
     */
    @Volatile
    private var schemaSupported: Boolean = true

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        var promptFile: Path? = null
        try {
            val file = promptFileWriter.write(request)
            promptFile = file
            // Локальная копия, а не поле: параллельный вызов может снять флаг между запуском и
            // разбором ошибки, и тогда запуск, который схему всё-таки передал, потерял бы повтор.
            val useSchema = schemaSupported
            logger.debug {
                "Grok request for recording ${request.recordingId}: model=$model, effort=${effortForLog()}, " +
                    "json-schema=${if (useSchema) "on" else "off"}, frames=${request.frames.size}"
            }
            var result = runGrok(file, useSchema)
            var errorMessage = outputParser.errorMessage(result.stdout)
            if (errorMessage != null && useSchema && exceptionMapper.isStructuredOutputUnsupported(errorMessage)) {
                logger.warn {
                    "Model $model does not accept --json-schema ($errorMessage); retrying without it " +
                        "and reading the JSON out of the response text from now on"
                }
                schemaSupported = false
                result = runGrok(file, structuredOutput = false)
                errorMessage = outputParser.errorMessage(result.stdout)
            }
            if (errorMessage != null) {
                throw exceptionMapper.fromFailure(result.exitCode, errorMessage, result.stderrTail)
            }
            if (result.exitCode != 0) {
                throw exceptionMapper.fromFailure(result.exitCode, null, result.stderrTail)
            }
            val output = outputParser.parse(result.stdout)
            logger.debug {
                "Grok describe for recording ${request.recordingId}: model=$model, effort=${effortForLog()}, " +
                    "fields=${if (output.fromText) "text" else "structuredOutput"}, ${output.usageSummary}, " +
                    "stopReason=${output.stopReason}, session=${output.sessionId}"
            }
            if (!output.short.isNullOrBlank() && !output.detailed.isNullOrBlank()) {
                return ResultNormalizer.normalize(output.short, output.detailed, request.shortMaxLength, request.detailedMaxLength)
            }
            throw exceptionMapper.fromStopReason(output.stopReason)
        } finally {
            promptFile?.let { promptFileWriter.delete(it) }
        }
    }

    private suspend fun runGrok(
        promptFile: Path,
        structuredOutput: Boolean,
    ): GrokProcessResult {
        val command = commandBuilder.build(promptFile, model, effort, structuredOutput)
        return guard.shared { runner.run(command) }
    }

    private fun effortForLog(): String = effort.ifBlank { "<none>" }

    companion object {
        const val PROVIDER_ID = "grok"

        const val AUTH_RECOVERY_HINT =
            "grok login --device-code (in Docker: docker compose exec frigate-analyzer grok login --device-code)"
    }
}
