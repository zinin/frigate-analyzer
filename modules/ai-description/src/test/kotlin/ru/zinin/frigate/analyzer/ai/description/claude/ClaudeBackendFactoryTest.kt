package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.mockk
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.core.VisionBackendFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClaudeBackendFactoryTest {
    private fun factory(
        oauthToken: String = "token",
        anthropicToken: String = "",
        modelOverride: String = "",
    ) = ClaudeBackendFactory(
        claudeProperties = properties(oauthToken, anthropicToken, modelOverride),
        promptBuilder = mockk(relaxed = true),
        imageStager = mockk(relaxed = true),
        invoker = { _, _, _, _ -> "{}" },
        exceptionMapper = mockk(relaxed = true),
    )

    private fun properties(
        oauthToken: String,
        anthropicToken: String,
        modelOverride: String = "",
    ) = ClaudeProperties(
        oauthToken = oauthToken,
        model = "opus",
        cliPath = "",
        workingDirectory = "/tmp",
        proxy = ClaudeProperties.ProxySection("", "", ""),
        anthropic = ClaudeProperties.AnthropicSection(authToken = anthropicToken, modelOverride = modelOverride),
    )

    private fun preset(model: String = "opus") = DescriptionProperties.Preset(provider = "claude", model = model)

    @Test
    fun `without any token the provider is unavailable instead of failing the startup`() {
        val availability = factory(oauthToken = "").availability()

        val unavailable = assertIs<VisionBackendFactory.Availability.Unavailable>(availability)
        assertEquals(UnavailableReason.NoToken, unavailable.reason)
    }

    /**
     * Перенесено из `ClaudeBackendValidationTest`: пробельный токен — это отсутствующий токен, и
     * пропускать такой деплой дальше нельзя. Раньше это ронял `init` backend-а, теперь помечает
     * пресет.
     */
    @Test
    fun `whitespace-only tokens count as absent`() {
        val availability = factory(oauthToken = "   ", anthropicToken = "   ").availability()

        assertEquals(
            UnavailableReason.NoToken,
            assertIs<VisionBackendFactory.Availability.Unavailable>(availability).reason,
        )
    }

    @Test
    fun `an oauth token alone makes the provider available`() {
        assertIs<VisionBackendFactory.Availability.Available>(factory(oauthToken = "token-xyz").availability())
    }

    @Test
    fun `an anthropic token alone makes the provider available`() {
        assertIs<VisionBackendFactory.Availability.Available>(
            factory(oauthToken = "", anthropicToken = "sk-ant").availability(),
        )
    }

    @Test
    fun `both tokens make the provider available`() {
        assertIs<VisionBackendFactory.Availability.Available>(
            factory(oauthToken = "token-xyz", anthropicToken = "sk-ant").availability(),
        )
    }

    @Test
    fun `the created backend carries the preset model`() {
        val backend = factory().create(preset(model = "sonnet"))

        assertEquals("claude", backend.providerId)
        assertEquals("sonnet", (backend as ClaudeBackend).model)
    }

    /** Один токен обслуживает все claude-модели, поэтому область у пресетов общая. */
    @Test
    fun `every claude preset shares one auth scope`() {
        val factory = factory()

        assertEquals("claude", factory.authScopeId(preset(model = "opus")))
        assertEquals("claude", factory.authScopeId(preset(model = "sonnet")))
    }

    @Test
    fun `the declared model is effective while no override is set`() {
        assertEquals("sonnet", factory().effectiveModel(preset(model = "sonnet")))
    }

    /** `ANTHROPIC_MODEL` вытесняет объявленную модель — экран обязан показывать ту, что уйдёт в запрос. */
    @Test
    fun `the anthropic override displaces the declared model`() {
        assertEquals(
            "gpt-5-via-gateway",
            factory(modelOverride = "gpt-5-via-gateway").effectiveModel(preset(model = "sonnet")),
        )
    }
}
