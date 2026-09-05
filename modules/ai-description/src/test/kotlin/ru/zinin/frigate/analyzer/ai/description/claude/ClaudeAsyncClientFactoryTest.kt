package ru.zinin.frigate.analyzer.ai.description.claude

import org.springframework.util.unit.DataSize
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeAsyncClientFactoryTest {
    private fun factory(props: ClaudeProperties) = ClaudeAsyncClientFactory(props)

    private fun props(
        token: String = "token-1",
        model: String = "opus",
        http: String = "",
        https: String = "",
        noProxy: String = "",
        authToken: String = "",
        baseUrl: String = "",
        modelOverride: String = "",
        defaultOpusModel: String = "",
        defaultSonnetModel: String = "",
        defaultHaikuModel: String = "",
        maxBufferSize: DataSize? = null,
    ): ClaudeProperties {
        val base =
            ClaudeProperties(
                oauthToken = token,
                model = model,
                cliPath = "",
                workingDirectory = "/tmp/frigate-analyzer",
                proxy = ClaudeProperties.ProxySection(http, https, noProxy),
                anthropic =
                    ClaudeProperties.AnthropicSection(
                        authToken = authToken,
                        baseUrl = baseUrl,
                        modelOverride = modelOverride,
                        defaultOpusModel = defaultOpusModel,
                        defaultSonnetModel = defaultSonnetModel,
                        defaultHaikuModel = defaultHaikuModel,
                    ),
            )
        return if (maxBufferSize == null) base else base.copy(maxBufferSize = maxBufferSize)
    }

    @Test
    fun `env map contains OAuth token`() {
        val env = factory(props()).buildEnvMap()
        assertEquals("token-1", env["CLAUDE_CODE_OAUTH_TOKEN"])
    }

    @Test
    fun `env map omits proxy vars when blank`() {
        val env = factory(props()).buildEnvMap()
        assertFalse(env.containsKey("HTTP_PROXY"))
        assertFalse(env.containsKey("HTTPS_PROXY"))
        assertFalse(env.containsKey("NO_PROXY"))
    }

    @Test
    fun `env map includes proxy vars when set`() {
        val env =
            factory(
                props(http = "http://proxy:80", https = "http://proxy:443", noProxy = "localhost"),
            ).buildEnvMap()
        assertEquals("http://proxy:80", env["HTTP_PROXY"])
        assertEquals("http://proxy:443", env["HTTPS_PROXY"])
        assertEquals("localhost", env["NO_PROXY"])
    }

    @Test
    fun `env map omits oauth token when blank`() {
        val env = factory(props(token = "", authToken = "dummy")).buildEnvMap()
        assertFalse(env.containsKey("CLAUDE_CODE_OAUTH_TOKEN"))
    }

    @Test
    fun `env map contains only expected keys when anthropic vars blank`() {
        val env = factory(props()).buildEnvMap()
        assertTrue(env.keys == setOf("CLAUDE_CODE_OAUTH_TOKEN"))
    }

    @Test
    fun `anthropic vars omitted when blank`() {
        val env = factory(props()).buildEnvMap()
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"))
        assertFalse(env.containsKey("ANTHROPIC_MODEL"))
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_OPUS_MODEL"))
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_SONNET_MODEL"))
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_HAIKU_MODEL"))
    }

    @Test
    fun `anthropic vars included when set`() {
        val env =
            factory(
                props(
                    authToken = "sk-sp-xxx",
                    baseUrl = "https://example.com/apps/anthropic",
                    modelOverride = "qwen3.5-plus",
                    defaultOpusModel = "qwen3.5-plus",
                    defaultSonnetModel = "qwen3.5-plus",
                    defaultHaikuModel = "qwen3.5-plus",
                ),
            ).buildEnvMap()
        assertEquals("sk-sp-xxx", env["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("https://example.com/apps/anthropic", env["ANTHROPIC_BASE_URL"])
        assertEquals("qwen3.5-plus", env["ANTHROPIC_MODEL"])
        assertEquals("qwen3.5-plus", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("qwen3.5-plus", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("qwen3.5-plus", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
    }

    @Test
    fun `create throws when no token configured`() {
        assertFailsWith<IllegalStateException> {
            factory(props(token = "", authToken = "")).create(Duration.ofMinutes(2), model = "opus")
        }
    }

    @Test
    fun `create passes validation with only oauth token`() {
        factory(props(token = "oauth-token", authToken = "")).create(Duration.ofMinutes(2), model = "opus")
    }

    @Test
    fun `create passes validation with only anthropic auth token`() {
        factory(props(token = "", authToken = "sk-sp-xxx")).create(Duration.ofMinutes(2), model = "opus")
    }

    @Test
    fun `options carry the model passed to the call, not the one from the properties`() {
        val options = factory(props(model = "from-properties")).buildOptions(Duration.ofMinutes(2), model = "opus")
        assertEquals("opus", options.model)
    }

    @Test
    fun `a non-blank anthropic model-override still displaces the model passed to the call`() {
        val options =
            factory(props(modelOverride = "qwen3.5-plus"))
                .buildOptions(Duration.ofMinutes(2), model = "opus")
        assertNull(options.model)
    }

    @Test
    fun `options carry the 16 MiB message buffer by default`() {
        val options = factory(props()).buildOptions(Duration.ofMinutes(2), model = "opus")
        assertEquals(16 * 1024 * 1024, options.effectiveMaxBufferSize)
    }

    @Test
    fun `options carry the configured message buffer`() {
        val options =
            factory(props(maxBufferSize = DataSize.ofMegabytes(32)))
                .buildOptions(Duration.ofMinutes(2), model = "opus")
        assertEquals(32 * 1024 * 1024, options.effectiveMaxBufferSize)
    }
}
