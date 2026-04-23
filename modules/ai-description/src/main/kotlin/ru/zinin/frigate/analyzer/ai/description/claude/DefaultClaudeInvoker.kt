package ru.zinin.frigate.analyzer.ai.description.claude

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Duration

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
class DefaultClaudeInvoker(
    private val clientFactory: ClaudeAsyncClientFactory,
    descriptionProperties: DescriptionProperties,
) : ClaudeInvoker {
    // Invoker depends only on work-timeout — pull it out of properties at DI time
    // so configuration errors surface as startup failure, not at first call.
    private val workTimeout: Duration = descriptionProperties.common.timeout

    override suspend fun invoke(prompt: String): String {
        val client = clientFactory.create(workTimeout)
        try {
            client.connect().awaitSingleOrNull() // Mono<Void>
            return client.query(prompt).text().awaitSingle() // Mono<String>
        } finally {
            // ClaudeAsyncClient is NOT AutoCloseable — .use not usable. Close explicitly.
            // NonCancellable required: invoke() runs under withTimeout in
            // ClaudeDescriptionAgent.describe(); on timeout the finally runs with Job
            // already cancelled, and a bare awaitSingleOrNull() would throw
            // CancellationException before subscribing — skipping the close and leaking
            // the Claude CLI subprocess.
            withContext(NonCancellable) {
                runCatching { client.close().awaitSingleOrNull() }
            }
        }
    }
}
