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

    private fun agent(token: String): ClaudeDescriptionAgent =
        ClaudeDescriptionAgent(
            claudeProperties =
                ClaudeProperties(
                    oauthToken = token,
                    model = "opus",
                    cliPath = "",
                    workingDirectory = "/tmp",
                    proxy = ClaudeProperties.ProxySection("", "", ""),
                ),
            descriptionProperties = descriptionProps,
            promptBuilder = mockk(),
            responseParser = mockk(),
            imageStager = mockk(),
            invoker = mockk(),
            exceptionMapper = mockk(),
        )

    @Test
    fun `init rejects blank oauth token`() {
        assertFailsWith<IllegalStateException> { agent("") }
    }

    @Test
    fun `init rejects blank oauth token with whitespace`() {
        assertFailsWith<IllegalStateException> { agent("   ") }
    }

    @Test
    fun `init accepts non-blank oauth token`() {
        agent("token-xyz") // no exception
    }
}
