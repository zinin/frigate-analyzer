package ru.zinin.frigate.analyzer.ai.description.claude

import ru.zinin.frigate.analyzer.ai.description.core.VisionBackend
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest

/**
 * Одна попытка через Claude Code CLI: кадры во временные jpg, промпт со ссылками `@/abs/path`,
 * вызов SDK. Семафор, таймауты, повторы и разбор ответа живут в `DefaultDescriptionAgent`.
 *
 * Не бин: экземпляр создаёт [ClaudeBackendFactory] на каждый claude-пресет, поэтому [model] и
 * [authScopeId] приходят из пресета, а осмотр окружения остаётся в фабрике — один раз на провайдер.
 */
class ClaudeBackend(
    val model: String,
    override val authScopeId: String,
    private val promptBuilder: ClaudePromptBuilder,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : VisionBackend {
    override val providerId: String = "claude"
    override val authRecoveryHint: String = AUTH_RECOVERY_HINT

    override suspend fun complete(request: VisionRequest): String {
        val stagedPaths = imageStager.stage(request)
        try {
            val prompt = promptBuilder.build(request, stagedPaths)
            return try {
                invoker.invoke(prompt, model, request.instructions.systemPrompt)
            } catch (e: Throwable) {
                throw exceptionMapper.map(e)
            }
        } finally {
            imageStager.cleanup(stagedPaths)
        }
    }

    companion object {
        const val AUTH_RECOVERY_HINT =
            "set CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token` (or ANTHROPIC_AUTH_TOKEN) and restart"
    }
}
