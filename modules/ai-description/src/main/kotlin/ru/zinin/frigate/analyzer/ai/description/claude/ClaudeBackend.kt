package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springaicommunity.claude.agent.sdk.Query
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Одна попытка описания через Claude Code CLI: кадры во временные jpg, промпт со ссылками
 * `@/abs/path`, вызов SDK, разбор JSON. Семафор, таймауты и повторы живут в
 * `DefaultDescriptionAgent`.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
class ClaudeBackend(
    private val claudeProperties: ClaudeProperties,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : DescriptionBackend {
    override val providerId: String = "claude"
    override val authRecoveryHint: String =
        "set CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token` (or ANTHROPIC_AUTH_TOKEN) and restart"

    init {
        check(claudeProperties.oauthToken.isNotBlank() || claudeProperties.anthropic.authToken.isNotBlank()) {
            "At least one of CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_AUTH_TOKEN must be set " +
                "when application.ai.description.enabled=true"
        }
        // CLI detection зависит от cliPath: пустой → which claude; non-empty → проверяем executable напрямую.
        if (claudeProperties.cliPath.isBlank()) {
            if (!Query.isCliInstalled()) {
                logger.warn {
                    "Claude CLI not found in PATH (Query.isCliInstalled()==false); all description " +
                        "requests will return fallback. Check Dockerfile ENV PATH=... and claude install."
                }
            }
        } else {
            val cliFile = Path.of(claudeProperties.cliPath)
            if (!Files.isExecutable(cliFile)) {
                logger.warn {
                    "Explicit claude.cli-path='${claudeProperties.cliPath}' not found or not executable; " +
                        "all description requests will return fallback."
                }
            }
        }
    }

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        val stagedPaths = imageStager.stage(request)
        try {
            val prompt = promptBuilder.build(request, stagedPaths)
            val raw =
                try {
                    invoker.invoke(prompt)
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
}
