package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.telegram.dto.AiSettingsViewState
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSettingsMessageRendererTest {
    // Тексты живут в бандлах и проверяются отдельно; здесь важны ключи, аргументы и payload.
    private val msg =
        mockk<MessageResolver>().apply {
            every { get(any(), any<String>()) } answers { firstArg() }
            every { get(any(), any<String>(), *anyVararg()) } answers { firstArg() }
        }
    private val renderer = AiSettingsMessageRenderer(msg)

    private fun preset(
        id: String,
        provider: String,
        model: String,
        effectiveModel: String = model,
        effort: String = "",
        authScopeId: String = provider,
        unavailableReason: UnavailableReason? = null,
        slowEffort: Boolean = false,
    ) = DescriptionPreset(
        id = id,
        provider = provider,
        model = model,
        effectiveModel = effectiveModel,
        effort = effort,
        authScopeId = authScopeId,
        unavailableReason = unavailableReason,
        slowEffort = slowEffort,
    )

    private val fast = preset("grok-fast", "grok", "grok-4.6", effort = "low", authScopeId = "grok:grok-4.6")
    private val luna = preset("byok-luna", "grok", "codex-luna", authScopeId = "grok:codex-luna")
    private val opus =
        preset("claude-opus", "claude", "opus", authScopeId = "claude", unavailableReason = UnavailableReason.NoToken)

    private fun state(
        enabled: Boolean = true,
        storedId: String? = "grok-fast",
        effectiveId: String? = "grok-fast",
        presets: List<DescriptionPreset> = listOf(fast, luna, opus),
        auth: Map<String, ProviderAuthStates.Health> = mapOf("grok:grok-4.6" to ProviderAuthStates.Health.HEALTHY),
    ) = AiSettingsViewState(
        descriptionsEnabled = enabled,
        storedPresetId = storedId,
        effectivePresetId = effectiveId,
        presets = presets,
        authByScope = auth,
        language = "ru",
    )

    private fun payloads(state: AiSettingsViewState): List<String> =
        renderer
            .render(state)
            .keyboard.keyboard
            .flatten()
            .map { (it as CallbackDataInlineKeyboardButton).callbackData }

    private fun labels(state: AiSettingsViewState): List<String> =
        renderer
            .render(state)
            .keyboard.keyboard
            .flatten()
            .map { (it as CallbackDataInlineKeyboardButton).text }

    @Test
    fun `the active preset line carries provider, model and effort`() {
        renderer.render(state())

        verify { msg.get("ai.settings.active", "ru", "grok-fast", "grok", "grok-4.6", "low") }
    }

    /** R19: экран обязан печатать модель, которая реально уйдёт в запрос, а не объявленную. */
    @Test
    fun `the active preset line carries the effective model, not the declared one`() {
        val displaced = preset("claude-opus", "claude", "opus", effectiveModel = "sonnet", authScopeId = "claude")

        renderer.render(state(storedId = "claude-opus", effectiveId = "claude-opus", presets = listOf(displaced)))

        verify { msg.get("ai.settings.active", "ru", "claude-opus", "claude", "sonnet", "—") }
    }

    @Test
    fun `a blank effort renders as a dash`() {
        renderer.render(state(storedId = "byok-luna", effectiveId = "byok-luna"))

        verify { msg.get("ai.settings.active", "ru", "byok-luna", "grok", "codex-luna", "—") }
    }

    @Test
    fun `each auth scope gets one line and an unconfigured one reports the reason`() {
        renderer.render(state())

        verify { msg.get("ai.settings.auth.healthy", "ru", "grok:grok-4.6") }
        verify { msg.get("ai.settings.auth.unknown", "ru", "grok:codex-luna") }
        verify { msg.get("ai.settings.auth.unavailable", "ru", "claude", "ai.settings.reason.noToken") }
    }

    /**
     * Смысл ключа авторизации — область учётных данных, а не провайдер: `grok-fast` и `grok-deep`
     * ходят одним `auth.json`, и вторая строка о той же области обещала бы вторую независимую
     * авторизацию, которую нечем чинить отдельно. `exactly = 1` здесь обязателен: голый `verify`
     * у MockK — «хотя бы раз», и дубль строки прошёл бы мимо него.
     */
    @Test
    fun `two presets sharing one auth scope get a single line`() {
        val deep = preset("grok-deep", "grok", "grok-4.6", effort = "xhigh", authScopeId = "grok:grok-4.6")

        val text = renderer.render(state(presets = listOf(fast, deep, luna))).text

        verify(exactly = 1) { msg.get("ai.settings.auth.healthy", "ru", "grok:grok-4.6") }
        verify(exactly = 1) { msg.get("ai.settings.auth.unknown", "ru", "grok:codex-luna") }
        assertEquals(1, text.lines().count { it == "ai.settings.auth.healthy" }, text)
    }

    @Test
    fun `a scope that was never called reads as unknown`() {
        renderer.render(state(auth = emptyMap()))

        verify { msg.get("ai.settings.auth.unknown", "ru", "grok:grok-4.6") }
    }

    @Test
    fun `a scope whose credentials were rejected reads as lost`() {
        renderer.render(state(auth = mapOf("grok:grok-4.6" to ProviderAuthStates.Health.LOST)))

        verify { msg.get("ai.settings.auth.lost", "ru", "grok:grok-4.6") }
    }

    /** Причина — замкнутый тип, и каждый его вариант получает собственный локализованный ключ. */
    @Test
    fun `every unavailable reason resolves to its own key`() {
        val unwritable =
            preset(
                "grok-fast",
                "grok",
                "grok-4.6",
                authScopeId = "grok:grok-4.6",
                unavailableReason = UnavailableReason.HomeUnwritable("/data/grok"),
            )
        val noFactory =
            preset("gemini-pro", "gemini", "pro", authScopeId = "gemini", unavailableReason = UnavailableReason.NoFactory("gemini"))

        renderer.render(state(effectiveId = null, presets = listOf(unwritable, noFactory)))

        verify { msg.get("ai.settings.reason.homeUnwritable", "ru", "/data/grok") }
        verify { msg.get("ai.settings.reason.noFactory", "ru", "gemini") }
    }

    @Test
    fun `the auth block is printed even when no preset is effective`() {
        val text = renderer.render(state(storedId = null, effectiveId = null)).text

        assertTrue(text.contains("ai.settings.active.none"), text)
        assertTrue(text.contains("ai.settings.auth.healthy"), text)
        assertTrue(text.contains("ai.settings.auth.note"), text)
    }

    @Test
    fun `every preset gets a button and the effective one is marked`() {
        val rendered = labels(state())

        assertTrue(rendered.contains("✅ grok-fast"), rendered.toString())
        assertTrue(rendered.contains("byok-luna"), rendered.toString())
        assertTrue(rendered.contains("⚠️ claude-opus"), rendered.toString())
        assertTrue(payloads(state()).containsAll(listOf("aip:set:grok-fast", "aip:set:claude-opus")))
    }

    /** ✅ стоит у эффективного пресета: владелец должен видеть, что работает, а не что записано. */
    @Test
    fun `the mark follows the effective preset, not the stored one`() {
        val rendered = labels(state(storedId = "claude-opus", effectiveId = "grok-fast"))

        assertTrue(rendered.contains("✅ grok-fast"), rendered.toString())
        assertFalse(rendered.contains("✅ claude-opus"), rendered.toString())
    }

    @Test
    fun `the switch button offers the opposite state`() {
        assertTrue(payloads(state(enabled = true)).contains("aip:off"))
        assertTrue(payloads(state(enabled = false)).contains("aip:on"))
    }

    @Test
    fun `a disabled screen says so and offers to enable`() {
        val rendered = renderer.render(state(enabled = false))

        verify { msg.get("ai.settings.state", "ru", "ai.settings.state.off") }
        assertTrue(
            rendered.keyboard.keyboard
                .flatten()
                .map { (it as CallbackDataInlineKeyboardButton).text }
                .contains("ai.settings.button.enable"),
        )
    }

    @Test
    fun `an empty catalog shows the none line and only close`() {
        val empty = state(storedId = null, effectiveId = null, presets = emptyList())

        assertTrue(renderer.render(empty).text.contains("ai.settings.active.none"))
        assertEquals(listOf("aip:close"), payloads(empty))
    }

    @Test
    fun `a stored preset that lost to the fallback is reported with its reason`() {
        renderer.render(state(storedId = "claude-opus", effectiveId = "grok-fast"))

        verify {
            msg.get(
                "ai.settings.active.mismatch",
                "ru",
                "claude-opus",
                "ai.settings.reason.noToken",
                "grok-fast",
                "ai.settings.mismatch.kept",
            )
        }
    }

    /** R21: id, которого больше нет в конфиге, — не «недоступен», а «не объявлен». */
    @Test
    fun `a stored preset that vanished from the config gets its own reason`() {
        renderer.render(state(storedId = "removed-preset", effectiveId = "grok-fast"))

        verify {
            msg.get(
                "ai.settings.active.mismatch",
                "ru",
                "removed-preset",
                "ai.settings.reason.gone",
                "grok-fast",
                "ai.settings.mismatch.kept",
            )
        }
    }

    /**
     * Сохранённый пресет цел и годен, а работает другой: `storedId()` и `effective()` — два
     * независимых fail-open чтения настроек, поэтому такая пара достижима, и `.gone`
     * («пресет больше не объявлен») был бы здесь прямой ложью.
     *
     * Следствие здесь обязано отличаться от остальных причин: «применится снова, когда пресет
     * станет доступен» о доступном пресете — вторая ложь на том же экране, поэтому строка ведёт к
     * перечитыванию, а не к ожиданию.
     */
    @Test
    fun `a stored preset that is present and usable reports an unknown reason`() {
        renderer.render(state(storedId = "byok-luna", effectiveId = "grok-fast"))

        verify {
            msg.get(
                "ai.settings.active.mismatch",
                "ru",
                "byok-luna",
                "ai.settings.reason.unknown",
                "grok-fast",
                "ai.settings.mismatch.recheck",
            )
        }
        verify(exactly = 0) { msg.get("ai.settings.mismatch.kept", "ru") }
    }

    @Test
    fun `there is no mismatch line when the stored preset is the effective one`() {
        val text = renderer.render(state()).text

        assertFalse(text.contains("ai.settings.active.mismatch"), text)
    }

    /** R22: медленный effort виден ДО клика — иначе о ловушке узнают по таймауту в проде. */
    @Test
    fun `a preset with no retry budget is marked and explained`() {
        val slow = preset("grok-deep", "grok", "grok-4.6", effort = "xhigh", authScopeId = "grok:grok-4.6", slowEffort = true)

        val rendered = renderer.render(state(presets = listOf(fast, slow)))
        val buttons =
            rendered.keyboard.keyboard
                .flatten()
                .map { (it as CallbackDataInlineKeyboardButton).text }

        assertTrue(buttons.contains("grok-deep 🐢"), buttons.toString())
        assertTrue(buttons.contains("✅ grok-fast"), buttons.toString())
        assertTrue(rendered.text.contains("ai.settings.slow.note"), rendered.text)
        verify { msg.get("ai.settings.slow.note", "ru", "🐢") }
    }

    @Test
    fun `without a slow preset there is no legend`() {
        assertFalse(renderer.render(state()).text.contains("ai.settings.slow.note"))
    }

    /**
     * R23: причина отказа в модалке проходит через тот же единственный `when` по
     * [UnavailableReason], что и экран. Второй `when` где-то ещё показал бы владельцу
     * `toString()` варианта — с путём или куском ключа — и молча пропустил бы четвёртый вариант.
     */
    @Test
    fun `the alert names the reason through the localized keys of the screen`() {
        renderer.alertText("ai.settings.alert.unavailable", AiSettingsAlertCause.Unavailable(UnavailableReason.NoToken), "ru")

        verify { msg.get("ai.settings.alert.unavailable", "ru", "ai.settings.reason.noToken") }
    }

    @Test
    fun `the alert argument of a reason with a parameter is resolved too`() {
        val cause = AiSettingsAlertCause.Unavailable(UnavailableReason.HomeUnwritable("/home/grok"))

        renderer.alertText("ai.settings.alert.unavailable", cause, "ru")

        verify { msg.get("ai.settings.reason.homeUnwritable", "ru", "/home/grok") }
        verify { msg.get("ai.settings.alert.unavailable", "ru", "ai.settings.reason.homeUnwritable") }
    }

    @Test
    fun `an id gone from the config is named by its own reason`() {
        renderer.alertText("ai.settings.alert.unavailable", AiSettingsAlertCause.Gone, "ru")

        verify { msg.get("ai.settings.alert.unavailable", "ru", "ai.settings.reason.gone") }
    }

    /** Исход без причины (клик не-владельца) несёт ключ без аргументов. */
    @Test
    fun `an alert without a cause is resolved as a plain key`() {
        assertEquals("common.error.owner.only", renderer.alertText("common.error.owner.only", null, "ru"))

        verify { msg.get("common.error.owner.only", "ru") }
    }
}
