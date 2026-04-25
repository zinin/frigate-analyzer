package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.mockk
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ClaudeDescriptionAgentValidationTest {
    private val common =
        DescriptionProperties.CommonSection(
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
            maxFrames = 10,
            queueTimeout = Duration.ofSeconds(30),
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
        )

    private val descriptionProps =
        DescriptionProperties(enabled = true, provider = "claude", common = common)

    private fun agent(
        oauthToken: String = "token",
        authToken: String = "",
    ): ClaudeDescriptionAgent =
        ClaudeDescriptionAgent(
            claudeProperties =
                ClaudeProperties(
                    oauthToken = oauthToken,
                    model = "opus",
                    cliPath = "",
                    workingDirectory = "/tmp",
                    proxy = ClaudeProperties.ProxySection("", "", ""),
                    anthropic = ClaudeProperties.AnthropicSection(authToken = authToken),
                ),
            descriptionProperties = descriptionProps,
            promptBuilder = mockk(),
            responseParser = mockk(),
            imageStager = mockk(),
            invoker = mockk(),
            exceptionMapper = mockk(),
        )

    @Test
    fun `init rejects when both tokens blank`() {
        assertFailsWith<IllegalStateException> { agent(oauthToken = "", authToken = "") }
    }

    @Test
    fun `init rejects when both tokens whitespace`() {
        assertFailsWith<IllegalStateException> { agent(oauthToken = "   ", authToken = "   ") }
    }

    @Test
    fun `init accepts oauth token only`() {
        agent(oauthToken = "token-xyz") // no exception
    }

    @Test
    fun `init accepts anthropic auth token only`() {
        agent(oauthToken = "", authToken = "sk-sp-xxx") // no exception
    }

    @Test
    fun `init accepts both tokens`() {
        agent(oauthToken = "token-xyz", authToken = "sk-sp-xxx") // no exception
    }
}
