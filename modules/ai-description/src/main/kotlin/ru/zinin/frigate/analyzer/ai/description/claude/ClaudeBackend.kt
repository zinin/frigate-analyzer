package ru.zinin.frigate.analyzer.ai.description.claude

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend

/**
 * Одна попытка описания через Claude Code CLI: кадры во временные jpg, промпт со ссылками
 * `@/abs/path`, вызов SDK, разбор JSON. Семафор, таймауты и повторы живут в
 * `DefaultDescriptionAgent`.
 *
 * Не бин: экземпляр создаёт [ClaudeBackendFactory] на каждый claude-пресет, поэтому [model]
 * приходит из пресета, а осмотр окружения остаётся в фабрике — один раз на провайдер.
 */
class ClaudeBackend(
    val model: String,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : DescriptionBackend {
    override val providerId: String = "claude"
    override val authRecoveryHint: String = AUTH_RECOVERY_HINT

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        val stagedPaths = imageStager.stage(request)
        try {
            val prompt = promptBuilder.build(request, stagedPaths)
            val raw =
                try {
                    invoker.invoke(prompt, model)
                } catch (e: Throwable) {
                    // map() пробрасывает CancellationException как есть, см. его KDoc.
                    throw exceptionMapper.map(e)
                }
            return responseParser.parse(raw, request.shortMaxLength, request.detailedMaxLength)
        } finally {
            // cleanup сам работает под NonCancellable.
            imageStager.cleanup(stagedPaths)
        }
    }

    companion object {
        const val AUTH_RECOVERY_HINT =
            "set CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token` (or ANTHROPIC_AUTH_TOKEN) and restart"
    }
}
