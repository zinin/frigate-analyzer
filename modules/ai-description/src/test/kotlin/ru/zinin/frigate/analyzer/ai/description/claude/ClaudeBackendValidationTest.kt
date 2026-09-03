package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.mockk
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ClaudeBackendValidationTest {
    private fun backend(
        oauthToken: String = "token",
        authToken: String = "",
    ): ClaudeBackend =
        ClaudeBackend(
            claudeProperties =
                ClaudeProperties(
                    oauthToken = oauthToken,
                    model = "opus",
                    cliPath = "",
                    workingDirectory = "/tmp",
                    proxy = ClaudeProperties.ProxySection("", "", ""),
                    anthropic = ClaudeProperties.AnthropicSection(authToken = authToken),
                ),
            promptBuilder = mockk(),
            responseParser = mockk(),
            imageStager = mockk(),
            invoker = mockk(),
            exceptionMapper = mockk(),
        )

    @Test
    fun `init rejects when both tokens blank`() {
        assertFailsWith<IllegalStateException> { backend(oauthToken = "", authToken = "") }
    }

    @Test
    fun `init rejects when both tokens whitespace`() {
        assertFailsWith<IllegalStateException> { backend(oauthToken = "   ", authToken = "   ") }
    }

    @Test
    fun `init accepts oauth token only`() {
        backend(oauthToken = "token-xyz")
    }

    @Test
    fun `init accepts anthropic auth token only`() {
        backend(oauthToken = "", authToken = "sk-sp-xxx")
    }

    @Test
    fun `init accepts both tokens`() {
        backend(oauthToken = "token-xyz", authToken = "sk-sp-xxx")
    }
}
