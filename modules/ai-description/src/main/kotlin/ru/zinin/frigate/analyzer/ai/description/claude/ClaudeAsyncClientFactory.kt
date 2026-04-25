package ru.zinin.frigate.analyzer.ai.description.claude

import org.springaicommunity.claude.agent.sdk.ClaudeAsyncClient
import org.springaicommunity.claude.agent.sdk.ClaudeClient
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import java.nio.file.Path
import java.time.Duration

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeAsyncClientFactory(
    private val claudeProperties: ClaudeProperties,
) {
    fun create(workTimeout: Duration): ClaudeAsyncClient {
        val options =
            CLIOptions
                .builder()
                .model(claudeProperties.model)
                .timeout(workTimeout)
                .env(buildEnvMap())
                .build()

        // workingDirectory ОБЯЗАТЕЛЕН для SDK 1.0.0 (AsyncSpec#build бросает
        // IllegalArgumentException("workingDirectory is required")).
        // claudePath опционален — если задан, SDK использует его вместо поиска в PATH.
        val spec =
            ClaudeClient
                .async(options)
                .workingDirectory(Path.of(claudeProperties.workingDirectory))
        if (claudeProperties.cliPath.isNotBlank()) {
            spec.claudePath(claudeProperties.cliPath)
        }
        return spec.build()
    }

    internal fun buildEnvMap(): Map<String, String> =
        buildMap {
            put("CLAUDE_CODE_OAUTH_TOKEN", claudeProperties.oauthToken)
            if (claudeProperties.proxy.http.isNotBlank()) {
                put("HTTP_PROXY", claudeProperties.proxy.http)
            }
            if (claudeProperties.proxy.https.isNotBlank()) {
                put("HTTPS_PROXY", claudeProperties.proxy.https)
            }
            if (claudeProperties.proxy.noProxy.isNotBlank()) {
                put("NO_PROXY", claudeProperties.proxy.noProxy)
            }
        }
}
