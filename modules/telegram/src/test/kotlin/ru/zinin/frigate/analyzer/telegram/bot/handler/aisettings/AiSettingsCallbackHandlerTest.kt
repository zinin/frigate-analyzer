package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPresets
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings.AiSettingsCallbackHandler.DispatchOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AiSettingsCallbackHandlerTest {
    private val settings = mockk<DescriptionRuntimeSettings>(relaxed = true)
    private val presets = mockk<DescriptionPresets>().also { every { it.all() } returns CATALOG }
    private val handler = AiSettingsCallbackHandler(provider(presets), provider(settings))

    /** Всё, чем коллбэк ответил владельцу: длина списка и есть проверка «ответ ровно один». */
    private val answers = mutableListOf<AiSettingsCallbackHandler.Dispatched>()

    private suspend fun handle(
        data: String,
        isOwner: Boolean = true,
        changedBy: String? = "owner",
    ) = handler.handle(data, isOwner, changedBy) { answers += it }

    @Test
    fun `the owner switches the preset`() =
        runTest {
            val dispatched = handle("aip:set:grok-deep")

            assertEquals(DispatchOutcome.RERENDER, dispatched.outcome)
            assertNull(dispatched.alertKey)
            coVerify(exactly = 1) { settings.setActivePresetId("grok-deep", "owner") }
        }

    /**
     * Порядок «сначала ответ, потом запись» — не косметика: дефолтный `markerFactory` сериализует
     * коллбэки одного пользователя, поэтому обработчик, ждущий медленную БД, задержит следующий
     * клик владельца, а не только спиннер.
     */
    @Test
    fun `the callback is answered before the write`() =
        runTest {
            val trace = mutableListOf<String>()
            val slowSettings = mockk<DescriptionRuntimeSettings>()
            coEvery { slowSettings.setActivePresetId(any(), any()) } answers { trace += "write" }
            val slowHandler = AiSettingsCallbackHandler(provider(presets), provider(slowSettings))

            slowHandler.handle("aip:set:grok-deep", isOwner = true, changedBy = "owner") { trace += "answer" }

            assertEquals(listOf("answer", "write"), trace)
        }

    @Test
    fun `an unavailable preset is refused with its reason and nothing is written`() =
        runTest {
            val dispatched = handle("aip:set:claude-opus")

            assertEquals(DispatchOutcome.ALERT, dispatched.outcome)
            assertEquals("ai.settings.alert.unavailable", dispatched.alertKey)
            assertEquals(AiSettingsAlertCause.Unavailable(UnavailableReason.NoToken), dispatched.alertCause)
            coVerify(exactly = 0) { settings.setActivePresetId(any(), any()) }
        }

    @Test
    fun `a preset that is gone from the config is refused`() =
        runTest {
            val dispatched = handle("aip:set:missing")

            assertEquals(DispatchOutcome.ALERT, dispatched.outcome)
            assertEquals("ai.settings.alert.unavailable", dispatched.alertKey)
            assertEquals(AiSettingsAlertCause.Gone, dispatched.alertCause)
            coVerify(exactly = 0) { settings.setActivePresetId(any(), any()) }
        }

    @Test
    fun `the switch writes an explicit value in both directions`() =
        runTest {
            assertEquals(DispatchOutcome.RERENDER, handle("aip:off").outcome)
            assertEquals(DispatchOutcome.RERENDER, handle("aip:on").outcome)

            coVerify(exactly = 1) { settings.setDescriptionsEnabled(false, "owner") }
            coVerify(exactly = 1) { settings.setDescriptionsEnabled(true, "owner") }
        }

    @Test
    fun `close closes and writes nothing`() =
        runTest {
            assertEquals(DispatchOutcome.CLOSE, handle("aip:close").outcome)

            coVerify(exactly = 0) { settings.setActivePresetId(any(), any()) }
            coVerify(exactly = 0) { settings.setDescriptionsEnabled(any(), any()) }
        }

    @Test
    fun `a non-owner changes nothing`() =
        runTest {
            val dispatched = handle("aip:set:grok-deep", isOwner = false, changedBy = "user")

            assertEquals(DispatchOutcome.UNAUTHORIZED, dispatched.outcome)
            assertEquals("common.error.owner.only", dispatched.alertKey)
            coVerify(exactly = 0) { settings.setActivePresetId(any(), any()) }
            coVerify(exactly = 0) { settings.setDescriptionsEnabled(any(), any()) }
        }

    @Test
    fun `a malformed payload is ignored`() =
        runTest {
            assertEquals(DispatchOutcome.IGNORE, handle("aip:set:").outcome)
            assertEquals(DispatchOutcome.IGNORE, handle("aip:").outcome)
            assertEquals(DispatchOutcome.IGNORE, handle("aip:nonsense").outcome)

            coVerify(exactly = 0) { settings.setActivePresetId(any(), any()) }
            coVerify(exactly = 0) { settings.setDescriptionsEnabled(any(), any()) }
        }

    /**
     * Именованная строка матрицы тестов: спиннер владельца обязан гаснуть на любом исходе, иначе
     * его снимет только таймаут Telegram.
     */
    @Test
    fun `every outcome answers the callback exactly once`() =
        runTest {
            val payloads =
                listOf(
                    "aip:set:grok-deep",
                    "aip:set:claude-opus",
                    "aip:set:missing",
                    "aip:set:",
                    "aip:on",
                    "aip:off",
                    "aip:close",
                    "aip:",
                    "aip:nonsense",
                )
            payloads.forEach { payload ->
                listOf(true, false).forEach { isOwner ->
                    answers.clear()
                    handle(payload, isOwner = isOwner)
                    assertEquals(1, answers.size, "payload=$payload isOwner=$isOwner")
                }
            }
        }

    @Test
    fun `a write that throws still leaves the callback answered`() =
        runTest {
            coEvery { settings.setActivePresetId(any(), any()) } throws RuntimeException("db down")

            val failure = assertFailsWith<RuntimeException> { handle("aip:set:grok-deep") }

            assertEquals("db down", failure.message)
            assertEquals(1, answers.size)
        }

    @Test
    fun `a catalog that throws refuses the click without a write and still answers`() =
        runTest {
            every { presets.all() } throws IllegalStateException("catalog broken")

            assertEquals(DispatchOutcome.IGNORE, handle("aip:set:grok-deep").outcome)

            assertEquals(1, answers.size)
            coVerify(exactly = 0) { settings.setActivePresetId(any(), any()) }
        }

    @Test
    fun `missing beans answer the callback and write nothing`() =
        runTest {
            val bare =
                AiSettingsCallbackHandler(
                    provider<DescriptionPresets>(null),
                    provider<DescriptionRuntimeSettings>(null),
                )
            val bareAnswers = mutableListOf<AiSettingsCallbackHandler.Dispatched>()

            val select = bare.handle("aip:set:grok-deep", isOwner = true, changedBy = "owner") { bareAnswers += it }
            val switch = bare.handle("aip:on", isOwner = true, changedBy = "owner") { bareAnswers += it }

            assertEquals(DispatchOutcome.IGNORE, select.outcome)
            assertEquals(DispatchOutcome.RERENDER, switch.outcome)
            assertEquals(2, bareAnswers.size)
        }

    private inline fun <reified T : Any> provider(value: T?): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }

    private companion object {
        val CATALOG =
            listOf(
                DescriptionPreset("grok-fast", "grok", "grok-4.6", "grok-4.6", "low", "grok:grok-4.6", null),
                DescriptionPreset("grok-deep", "grok", "grok-4.6", "grok-4.6", "xhigh", "grok:grok-4.6", null),
                DescriptionPreset("claude-opus", "claude", "opus", "opus", "", "claude", UnavailableReason.NoToken),
            )
    }
}
