package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import org.springaicommunity.claude.agent.sdk.types.AssistantMessage
import org.springaicommunity.claude.agent.sdk.types.ResultMessage
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DefaultClaudeInvoker(
    private val clientFactory: ClaudeAsyncClientFactory,
) : ClaudeInvoker {
    override suspend fun invoke(
        prompt: String,
        model: String,
        systemPrompt: String,
        timeout: Duration,
    ): String {
        logger.debug { "Claude prompt (${prompt.length} chars):\n$prompt" }
        // Бюджет приходит от задачи, а не из настроек описаний: у судьи свой таймаут, и клиент,
        // построенный по чужому, обрывал бы его вызов раньше срока и отдавал бы это как Transport.
        //
        // Буфер поверх бюджета намеренный: `withTimeout` корутины обязан сработать первым. При
        // равных значениях выиграл бы тот, кто успел, а типы исключений разные (SDK → Transport →
        // повтор с delay(5s) против Kotlin → Timeout без повтора), и поведение у границы бюджета
        // стало бы недетерминированным. Сторона SDK остаётся страховкой.
        val client = clientFactory.create(timeout.plus(SDK_TIMEOUT_BUFFER), model, systemPrompt)
        try {
            // We send the prompt as the FIRST user message via `connect(prompt)`.
            // The no-arg `connect()` would inject a default "Hello" message before ours, and the
            // bidirectional stream then closes the turn on the ResultMessage produced for that
            // "Hello" — so the assistant's response to our real prompt would never be read.
            //
            // We use `.messages()` instead of `.text()` because `text()` silently drops non-assistant
            // messages — including `ResultMessage` with `isError=true`, which is how the CLI signals
            // in-band rate-limit / auth / execution errors. With `.text()` those errors surface as
            // an empty string and are mis-classified as `InvalidResponse`, costing an extra retry
            // and hiding the real cause (e.g. a 429 → Transport instead of RateLimited).
            val messages =
                client
                    .connect(prompt)
                    .messages()
                    .collectList()
                    .awaitSingle()

            val result = messages.filterIsInstance<ResultMessage>().firstOrNull()
            if (result != null && result.isError) {
                val detail =
                    buildString {
                        append("Claude returned error result")
                        result.subtype()?.takeIf { it.isNotBlank() }?.let { append(" (subtype=$it)") }
                        result.result()?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                    }
                // ClaudeExceptionMapper decides RateLimited vs Transport from the exception message.
                throw ClaudeSDKException(detail)
            }

            val rawText =
                messages
                    .filterIsInstance<AssistantMessage>()
                    .joinToString(separator = "") { it.text() }
            logger.debug { "Claude raw response (${rawText.length} chars):\n$rawText" }
            return rawText
        } finally {
            // ClaudeAsyncClient is NOT AutoCloseable — .use not usable. Close explicitly.
            // NonCancellable required: invoke() runs under withTimeout in
            // VisionCallExecutor; on timeout the finally runs with Job
            // already cancelled, and a bare awaitSingleOrNull() would throw
            // CancellationException before subscribing — skipping the close and leaking
            // the Claude CLI subprocess.
            withContext(NonCancellable) {
                runCatching { client.close().awaitSingleOrNull() }
            }
        }
    }

    companion object {
        private val SDK_TIMEOUT_BUFFER: Duration = Duration.ofSeconds(5)
    }
}
