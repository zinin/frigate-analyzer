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
        check(claudeProperties.oauthToken.isNotBlank() || claudeProperties.anthropic.authToken.isNotBlank()) {
            "At least one of CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_AUTH_TOKEN must be set " +
                "when application.ai.description.enabled=true"
        }
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
            if (claudeProperties.oauthToken.isNotBlank()) {
                put("CLAUDE_CODE_OAUTH_TOKEN", claudeProperties.oauthToken)
            }
            val proxy = claudeProperties.proxy
            if (proxy.http.isNotBlank()) {
                put("HTTP_PROXY", proxy.http)
            }
            if (proxy.https.isNotBlank()) {
                put("HTTPS_PROXY", proxy.https)
            }
            if (proxy.noProxy.isNotBlank()) {
                put("NO_PROXY", proxy.noProxy)
            }
            val ap = claudeProperties.anthropic
            if (ap.authToken.isNotBlank()) {
                put("ANTHROPIC_AUTH_TOKEN", ap.authToken)
            }
            if (ap.baseUrl.isNotBlank()) {
                put("ANTHROPIC_BASE_URL", ap.baseUrl)
            }
            if (ap.modelOverride.isNotBlank()) {
                put("ANTHROPIC_MODEL", ap.modelOverride)
            }
            if (ap.defaultOpusModel.isNotBlank()) {
                put("ANTHROPIC_DEFAULT_OPUS_MODEL", ap.defaultOpusModel)
            }
            if (ap.defaultSonnetModel.isNotBlank()) {
                put("ANTHROPIC_DEFAULT_SONNET_MODEL", ap.defaultSonnetModel)
            }
            if (ap.defaultHaikuModel.isNotBlank()) {
                put("ANTHROPIC_DEFAULT_HAIKU_MODEL", ap.defaultHaikuModel)
            }
        }
}
