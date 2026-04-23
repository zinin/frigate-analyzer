package ru.zinin.frigate.analyzer.ai.description.claude

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
class DefaultClaudeInvoker(
    private val clientFactory: ClaudeAsyncClientFactory,
    descriptionProperties: DescriptionProperties,
) : ClaudeInvoker {
    // Invoker depends only on work-timeout — pull it out of properties at DI time
    // so configuration errors surface as startup failure, not at first call.
    private val workTimeout: java.time.Duration = descriptionProperties.common.timeout

    override suspend fun invoke(prompt: String): String {
        val client = clientFactory.create(workTimeout)
        try {
            client.connect().awaitSingleOrNull() // Mono<Void>
            return client.query(prompt).text().awaitSingle() // Mono<String>
        } finally {
            // ClaudeAsyncClient is NOT AutoCloseable — .use is not usable. Close explicitly.
            client.close().awaitSingleOrNull()
        }
    }
}
