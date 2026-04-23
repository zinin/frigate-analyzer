package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.springaicommunity.claude.agent.sdk.Query
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

private val logger = KotlinLogging.logger {}

/**
 * Agent активен только при enabled=true AND provider=claude. Модуль helpers (promptBuilder,
 * responseParser, imageStager, ...) регистрируются @Component только при enabled=true, без условия
 * на provider — они переиспользуются будущими провайдерами.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
class ClaudeDescriptionAgent(
    private val claudeProperties: ClaudeProperties,
    private val descriptionProperties: DescriptionProperties,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : DescriptionAgent {
    private val commonSection: DescriptionProperties.CommonSection = descriptionProperties.common
    private val semaphore = Semaphore(commonSection.maxConcurrent)

    init {
        check(claudeProperties.oauthToken.isNotBlank()) {
            "CLAUDE_CODE_OAUTH_TOKEN must be set when application.ai.description.enabled=true"
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
        try {
            withTimeout(commonSection.queueTimeout.toMillis()) {
                semaphore.acquire()
            }
        } catch (e: TimeoutCancellationException) {
            throw DescriptionException.Timeout(cause = e)
        }

        val callStart = System.nanoTime()
        try {
            return try {
                withTimeout(commonSection.timeout.toMillis()) {
                    val stagedPaths = imageStager.stage(request)
                    try {
                        val prompt = promptBuilder.build(request, stagedPaths)
                        executeWithRetry(prompt, request)
                    } finally {
                        imageStager.cleanup(stagedPaths)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw DescriptionException.Timeout(cause = e)
            }
        } finally {
            val elapsedMs = (System.nanoTime() - callStart) / 1_000_000
            logger.debug { "Claude describe completed in ${elapsedMs}ms for recording ${request.recordingId}" }
            semaphore.release()
        }
    }

    private suspend fun executeWithRetry(
        prompt: String,
        request: DescriptionRequest,
    ): DescriptionResult {
        val overallStart = TimeSource.Monotonic.markNow()
        val totalBudget = commonSection.timeout.toKotlinDuration()
        var jsonRetries = 0
        var transportRetries = 0
        while (true) {
            try {
                val raw =
                    try {
                        invoker.invoke(prompt)
                    } catch (e: Throwable) {
                        throw exceptionMapper.map(e)
                    }
                return responseParser.parse(raw, request.shortMaxLength, request.detailedMaxLength)
            } catch (e: DescriptionException.InvalidResponse) {
                if (jsonRetries >= 1) throw e
                jsonRetries++
                logger.warn(e) { "Invalid JSON from Claude, retrying (attempt ${jsonRetries + 1})" }
            } catch (e: DescriptionException.Transport) {
                if (transportRetries >= 1) throw e
                transportRetries++
                val elapsed = overallStart.elapsedNow()
                val remaining = totalBudget - elapsed
                if (remaining <= 7.seconds) {
                    logger.warn(e) { "Claude transport error but retry budget exhausted (remaining=$remaining); giving up" }
                    throw e
                }
                logger.warn(e) { "Claude transport error, retrying in 5s (remaining budget=$remaining)" }
                delay(5.seconds)
            }
        }
    }
}
