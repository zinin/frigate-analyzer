package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.api.ActiveDescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.ActiveJudgePreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPresets
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSettingsViewStateFactoryTest {
    private val presets = mockk<DescriptionPresets>()
    private val active = mockk<ActiveDescriptionPreset>()
    private val runtimeSettings = mockk<DescriptionRuntimeSettings>()
    private val authStates = mockk<ProviderAuthStates>()
    private val judgeRuntimeSettings = mockk<JudgeRuntimeSettings>()
    private val activeJudge = mockk<ActiveJudgePreset>()

    private fun preset(
        id: String,
        provider: String = "grok",
        model: String = "grok-4.6",
        authScopeId: String = "$provider:$model",
        unavailableReason: UnavailableReason? = null,
    ) = DescriptionPreset(
        id = id,
        provider = provider,
        model = model,
        effectiveModel = model,
        effort = "low",
        authScopeId = authScopeId,
        unavailableReason = unavailableReason,
    )

    private val fast = preset("grok-fast")
    private val opus =
        preset("claude-opus", provider = "claude", model = "opus", authScopeId = "claude", unavailableReason = UnavailableReason.NoToken)

    /** Бины каталога может не быть вовсе: пресеты не объявлены, а бот обязан стартовать. */
    private fun <T : Any> provider(value: T?) = mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }

    private fun factory(
        presets: DescriptionPresets? = this.presets,
        active: ActiveDescriptionPreset? = this.active,
        runtimeSettings: DescriptionRuntimeSettings? = this.runtimeSettings,
        authStates: ProviderAuthStates? = this.authStates,
        judgeRuntimeSettings: JudgeRuntimeSettings? = null,
        activeJudge: ActiveJudgePreset? = null,
    ) = AiSettingsViewStateFactory(
        provider(presets),
        provider(active),
        provider(runtimeSettings),
        provider(authStates),
        provider(judgeRuntimeSettings),
        provider(activeJudge),
    )

    @Test
    fun `the state carries both the stored and the effective id`() =
        runTest {
            every { presets.all() } returns listOf(fast, opus)
            every { authStates.byScope() } returns mapOf("grok:grok-4.6" to ProviderAuthStates.Health.HEALTHY)
            coEvery { runtimeSettings.descriptionsEnabled() } returns true
            coEvery { active.storedId() } returns "claude-opus"
            coEvery { active.effective() } returns fast

            val state = factory().build("ru")

            assertEquals("claude-opus", state.storedPresetId)
            assertEquals("grok-fast", state.effectivePresetId)
            assertTrue(state.hasMismatch)
            assertEquals(listOf("grok-fast", "claude-opus"), state.presets.map { it.id })
            assertEquals(mapOf("grok:grok-4.6" to ProviderAuthStates.Health.HEALTHY), state.authByScope)
            assertEquals("ru", state.language)
            assertTrue(state.descriptionsEnabled)
        }

    /** `byScope()` строит карту заново на каждый вызов, поэтому снимок снимается ровно один. */
    @Test
    fun `auth states are read once per build`() =
        runTest {
            every { presets.all() } returns listOf(fast, opus)
            every { authStates.byScope() } returns emptyMap()
            coEvery { runtimeSettings.descriptionsEnabled() } returns true
            coEvery { active.storedId() } returns null
            coEvery { active.effective() } returns fast

            factory().build("ru")

            verify(exactly = 1) { authStates.byScope() }
        }

    @Test
    fun `an undeclared catalog leaves no effective preset and skips resolution`() =
        runTest {
            coEvery { runtimeSettings.descriptionsEnabled() } returns false

            val state = factory(presets = null, active = null, authStates = null).build("en")

            assertTrue(state.presets.isEmpty())
            assertNull(state.storedPresetId)
            assertNull(state.effectivePresetId)
            assertTrue(state.authByScope.isEmpty())
            assertFalse(state.descriptionsEnabled)
            assertFalse(state.hasMismatch)
        }

    /** Каталог есть, но пуст: резолюции нет — спрашивать эффективный пресет не у чего. */
    @Test
    fun `an empty catalog does not ask for the effective preset`() =
        runTest {
            every { presets.all() } returns emptyList()
            every { authStates.byScope() } returns emptyMap()
            coEvery { runtimeSettings.descriptionsEnabled() } returns true
            coEvery { active.storedId() } returns "grok-fast"

            val state = factory().build("ru")

            assertNull(state.effectivePresetId)
            coVerify(exactly = 0) { active.effective() }
        }

    /**
     * Экран открывают ровно тогда, когда что-то сломалось: отказ `app_settings` не должен уносить
     * с собой список пресетов и состояние авторизации, которые читаются мимо базы.
     */
    @Test
    fun `a failing settings read leaves the rest of the screen intact`() =
        runTest {
            every { presets.all() } returns listOf(fast, opus)
            every { authStates.byScope() } returns mapOf("claude" to ProviderAuthStates.Health.LOST)
            coEvery { runtimeSettings.descriptionsEnabled() } throws IllegalStateException("app_settings is down")
            coEvery { active.storedId() } returns "grok-fast"
            coEvery { active.effective() } returns fast

            val state = factory().build("ru")

            assertTrue(state.descriptionsEnabled)
            assertEquals(listOf("grok-fast", "claude-opus"), state.presets.map { it.id })
            assertEquals(mapOf("claude" to ProviderAuthStates.Health.LOST), state.authByScope)
            assertEquals("grok-fast", state.effectivePresetId)
        }

    /** Без реализации настроек описания считаются включёнными: статический флаг фичи главнее. */
    @Test
    fun `a missing settings implementation reads as enabled`() =
        runTest {
            every { presets.all() } returns listOf(fast)
            every { authStates.byScope() } returns emptyMap()
            coEvery { active.storedId() } returns null
            coEvery { active.effective() } returns fast

            val state = factory(runtimeSettings = null).build("ru")

            assertTrue(state.descriptionsEnabled)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a hanging switch read fails open instead of blocking the screen`() =
        runTest {
            every { presets.all() } returns listOf(fast)
            every { authStates.byScope() } returns emptyMap()
            coEvery { runtimeSettings.descriptionsEnabled() } coAnswers {
                delay(60_000)
                false
            }
            coEvery { active.storedId() } returns "grok-fast"
            coEvery { active.effective() } returns fast

            val job = async { factory().build("ru") }
            advanceTimeBy(6_000)
            advanceUntilIdle()
            val state = job.await()

            assertTrue(state.descriptionsEnabled)
        }

    @Test
    fun `judge beans fill the judge section of the state`() =
        runTest {
            every { presets.all() } returns listOf(fast, opus)
            every { authStates.byScope() } returns emptyMap()
            coEvery { runtimeSettings.descriptionsEnabled() } returns true
            coEvery { active.storedId() } returns "grok-fast"
            coEvery { active.effective() } returns fast
            coEvery { judgeRuntimeSettings.judgeEnabled() } returns false
            coEvery { activeJudge.storedId() } returns "claude-opus"
            coEvery { activeJudge.effective() } returns fast

            val state = factory(judgeRuntimeSettings = judgeRuntimeSettings, activeJudge = activeJudge).build("ru")

            assertTrue(state.judgeAvailable)
            assertFalse(state.judgeEnabled)
            assertEquals("claude-opus", state.judgeStoredPresetId)
            assertEquals("grok-fast", state.judgeEffectivePresetId)
            assertTrue(state.hasJudgeMismatch)
        }

    @Test
    fun `missing judge beans hide the judge section`() =
        runTest {
            every { presets.all() } returns listOf(fast)
            every { authStates.byScope() } returns emptyMap()
            coEvery { runtimeSettings.descriptionsEnabled() } returns true
            coEvery { active.storedId() } returns "grok-fast"
            coEvery { active.effective() } returns fast

            val state = factory().build("ru")

            assertFalse(state.judgeAvailable)
            assertTrue(state.judgeEnabled)
            assertNull(state.judgeStoredPresetId)
            assertNull(state.judgeEffectivePresetId)
            assertFalse(state.hasJudgeMismatch)
        }

    @Test
    fun `a failing judge switch read fails open`() =
        runTest {
            every { presets.all() } returns listOf(fast)
            every { authStates.byScope() } returns emptyMap()
            coEvery { runtimeSettings.descriptionsEnabled() } returns true
            coEvery { active.storedId() } returns "grok-fast"
            coEvery { active.effective() } returns fast
            coEvery { judgeRuntimeSettings.judgeEnabled() } throws IllegalStateException("app_settings is down")
            coEvery { activeJudge.storedId() } returns "grok-fast"
            coEvery { activeJudge.effective() } returns fast

            val state = factory(judgeRuntimeSettings = judgeRuntimeSettings, activeJudge = activeJudge).build("ru")

            assertTrue(state.judgeAvailable)
            assertTrue(state.judgeEnabled)
        }
}
