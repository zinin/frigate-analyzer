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
    // Defaults to wall-clock; tests inject `runTest` virtual-time `TestTimeSource` so the retry
    // time-budget check behaves consistently with the outer `withTimeout` (also virtual-time).
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : DescriptionAgent {
    private val commonSection: DescriptionProperties.CommonSection = descriptionProperties.common
    private val semaphore = Semaphore(commonSection.maxConcurrent)

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
        val overallStart = timeSource.markNow()
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
                val elapsed = overallStart.elapsedNow()
                val remaining = totalBudget - elapsed
                if (remaining <= INVALID_RESPONSE_RETRY_MIN_BUDGET) {
                    logger.warn(e) { "Invalid JSON from Claude but retry budget exhausted (remaining=$remaining); giving up" }
                    throw e
                }
                logger.warn(e) { "Invalid JSON from Claude, retrying (attempt ${jsonRetries + 1}, remaining budget=$remaining)" }
            } catch (e: DescriptionException.Transport) {
                if (transportRetries >= 1) throw e
                transportRetries++
                val elapsed = overallStart.elapsedNow()
                val remaining = totalBudget - elapsed
                if (remaining <= TRANSPORT_RETRY_MIN_BUDGET) {
                    logger.warn(e) { "Claude transport error but retry budget exhausted (remaining=$remaining); giving up" }
                    throw e
                }
                logger.warn(e) { "Claude transport error, retrying in $TRANSPORT_RETRY_DELAY (remaining budget=$remaining)" }
                delay(TRANSPORT_RETRY_DELAY)
            }
        }
    }

    companion object {
        private val TRANSPORT_RETRY_DELAY = 5.seconds

        // Minimum remaining time-budget before we even attempt a retry:
        // TRANSPORT_RETRY_DELAY (pre-call sleep) + margin for one realistic Claude invocation.
        // If `remaining` is less than this, the outer `withTimeout(commonSection.timeout)` would
        // cancel mid-retry — giving up early produces a clean `Transport` error instead of
        // a misleading `Timeout`.
        private val TRANSPORT_RETRY_MIN_BUDGET = 10.seconds

        // Same rationale for InvalidResponse, minus TRANSPORT_RETRY_DELAY — we retry immediately
        // (no pre-call sleep), so the minimum is just "time for one realistic Claude invocation".
        private val INVALID_RESPONSE_RETRY_MIN_BUDGET = 5.seconds
    }
}
