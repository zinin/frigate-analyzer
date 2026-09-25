package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.core.VisionBackend
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import ru.zinin.frigate.analyzer.ai.description.core.VisionResponse
import java.nio.file.Path
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Одна попытка через headless Grok Build: `prompt.json` → процесс → сырой текст модели.
 * Семафор, таймауты, повторы и разбор ответа живут в `VisionCallExecutor`; отмена корутины
 * по таймауту убивает процесс в runner-е, а prompt-файл удаляется в `finally` под NonCancellable.
 *
 * Не бин: экземпляр создаёт [GrokBackendFactory] на каждый grok-пресет, поэтому [model], [effort]
 * и [authScopeId] приходят из пресета, а осмотр окружения остаётся в фабрике — один раз на провайдер.
 *
 * После каждого запуска [stripDetector] проверяет, дошли ли кадры до модели: grok молча выкидывает
 * картинки, если эндпоинт их отверг, и такой ответ отвергается как `InvalidResponse`.
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
    private val stripDetector: GrokImageStripDetector,
) : VisionBackend {
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

    /**
     * [timeout] не используется: у процесса grok нет собственного таймаута, его снимает отмена
     * корутины из `withTimeout` executor-а, и runner убивает процесс.
     */
    override suspend fun complete(
        request: VisionRequest,
        timeout: Duration,
    ): VisionResponse {
        var promptFile: Path? = null
        try {
            val file = promptFileWriter.write(request)
            promptFile = file
            val schema = request.instructions.jsonSchema
            val useSchema = schemaSupported && schema != null
            val systemPrompt = "${request.instructions.systemPrompt} $TOOL_RULE"
            logger.debug {
                "Grok request ${request.requestId}: model=$model, effort=${effortForLog()}, " +
                    "json-schema=${if (useSchema) "on" else "off"}, frames=${request.frames.size}"
            }
            var run = runGrok(file, useSchema, schema, systemPrompt)
            var errorMessage = outputParser.errorMessage(run.result.stdout)
            if (errorMessage != null && useSchema && exceptionMapper.isStructuredOutputUnsupported(errorMessage)) {
                logger.warn { "Model $model does not accept --json-schema ($errorMessage); retrying without it" }
                schemaSupported = false
                run = runGrok(file, structuredOutput = false, schema, systemPrompt)
                errorMessage = outputParser.errorMessage(run.result.stdout)
            }
            val result = run.result
            if (errorMessage != null) throw exceptionMapper.fromFailure(result.exitCode, errorMessage, result.stderrTail)
            if (result.exitCode != 0) throw exceptionMapper.fromFailure(result.exitCode, null, result.stderrTail)
            val output = outputParser.parse(result.stdout)
            // Проверяется запуск, чей ответ используется: при повторе без схемы это второй.
            val strip = if (request.frames.isEmpty()) StripCheck.Clean else stripDetector.inspect(run.log, output.sessionId)
            logger.debug {
                "Grok call ${request.requestId}: model=$model, effort=${effortForLog()}, " +
                    "primary=${if (output.fromText) "text" else "structuredOutput"}, " +
                    "fallback=${if (output.fallback != null) "text" else "none"}, ${output.usageSummary}, " +
                    "stopReason=${output.stopReason}, session=${output.sessionId}, strip=${strip.logName()}"
            }
            when (strip) {
                is StripCheck.Stripped -> throw rejectStripped(request, output, strip)
                is StripCheck.Blind -> stripDetector.reportBlind(strip.reason)
                StripCheck.Clean -> Unit
            }
            val payload =
                output.payload?.takeUnless { it.isBlank() }
                    ?: throw exceptionMapper.fromStopReason(output.stopReason)
            return VisionResponse(payload, output.fallback?.takeUnless { it.isBlank() })
        } finally {
            promptFile?.let { promptFileWriter.delete(it) }
        }
    }

    /**
     * Ответ получен после того, как grok выкинул картинки: модель их не видела. Смысл тот же, что у
     * гейта Claude («ответил, не прочитав кадр»), поэтому и тип тот же — `InvalidResponse`, который
     * executor повторяет один раз. Фрагмент ответа уходит в лог: по нему отличают вежливое «кадры
     * недоступны» от выдумки. Подсказка в тексте общая — id пресета backend не знает.
     */
    private fun rejectStripped(
        request: VisionRequest,
        output: GrokOutput,
        strip: StripCheck.Stripped,
    ): DescriptionException.InvalidResponse {
        val frames = request.frames.size
        val reasons = strip.reasons.joinToString().ifEmpty { "unknown" }
        logger.warn {
            "Grok dropped ${strip.images} of $frames frame(s) for ${request.requestId} " +
                "(model=$model, reason=$reasons); rejecting the answer: " +
                (output.payload?.take(ANSWER_LOG_MAX) ?: "<no answer>")
        }
        return DescriptionException.InvalidResponse(
            detail =
                "grok dropped ${strip.images} of $frames frame(s) before the model call (reason=$reasons); " +
                    "if the endpoint limits request size, set max-image-side on the preset",
        )
    }

    private suspend fun runGrok(
        promptFile: Path,
        structuredOutput: Boolean,
        jsonSchema: String?,
        systemPrompt: String,
    ): GrokRun {
        val command = commandBuilder.build(promptFile, model, effort, structuredOutput, jsonSchema, systemPrompt)
        // Хвост лога снимается под тем же shared, что и запуск: иначе ежечасная уборка могла бы
        // удалить unified.jsonl между выходом процесса и чтением.
        return guard.shared {
            val result = runner.run(command)
            GrokRun(result, withContext(Dispatchers.IO) { stripDetector.capture() })
        }
    }

    private fun effortForLog(): String = effort.ifBlank { "<none>" }

    private fun StripCheck.logName(): String =
        when (this) {
            StripCheck.Clean -> "clean"
            is StripCheck.Stripped -> "stripped"
            is StripCheck.Blind -> "blind"
        }

    /** Результат процесса и хвост `unified.jsonl`, снятый, пока запуск ещё держит `GrokHomeGuard.shared`. */
    private data class GrokRun(
        val result: GrokProcessResult,
        val log: CapturedLog,
    )

    companion object {
        const val PROVIDER_ID = "grok"

        /**
         * Кадры Grok получает image-блоками в prompt-файле, читать ему нечего: `read_file` и так
         * снят `--disallowed-tools`. Запрет живёт здесь, потому что общий текст задачи его больше
         * не несёт — Claude там же требует обратного, вызова Read.
         */
        const val TOOL_RULE = "Do not call tools."

        const val AUTH_RECOVERY_HINT =
            "grok login --device-code (in Docker: docker compose exec frigate-analyzer grok login --device-code)"

        /** Сколько символов отвергнутого ответа уходит в лог — столько же, сколько у гейта Claude. */
        private const val ANSWER_LOG_MAX = 300
    }
}
