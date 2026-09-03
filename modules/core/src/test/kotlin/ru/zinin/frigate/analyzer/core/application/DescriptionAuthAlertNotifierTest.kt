package ru.zinin.frigate.analyzer.core.application

import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DescriptionAuthAlertNotifierTest {
    private val telegramNotificationService = mockk<TelegramNotificationService>()
    private val messageResolver = mockk<MessageResolver>()
    private val notifier = DescriptionAuthAlertNotifier(telegramNotificationService, messageResolver)

    @AfterEach
    fun tearDown() {
        notifier.shutdown()
    }

    private fun awaitAlert() =
        runBlocking {
            notifier.scope.coroutineContext.job.children
                .toList()
                .joinAll()
        }

    private fun captureBuilder(): CapturingSlot<(String) -> String> {
        val builderSlot = slot<(String) -> String>()
        coEvery { telegramNotificationService.sendOwnerMessage(capture(builderSlot)) } just Runs
        return builderSlot
    }

    private fun lost(detail: String? = "Not signed in. Run grok login --device-code") =
        DescriptionProviderAuthEvent(
            provider = "grok",
            state = DescriptionProviderAuthEvent.State.LOST,
            detail = detail,
            recoveryHint = "grok login --device-code",
        )

    @Test
    fun `LOST sends the localized text with provider, hint and detail`() {
        every { messageResolver.get("ai.description.auth.lost", "ru", "grok", "grok login --device-code") } returns
            "🔴 grok: вход. Команда: grok login --device-code"
        val builder = captureBuilder()

        notifier.onAuthEvent(lost())
        awaitAlert()

        coVerify(exactly = 1) { telegramNotificationService.sendOwnerMessage(any()) }
        val text = builder.captured.invoke("ru")
        assertTrue(text.startsWith("🔴 grok: вход. Команда: grok login --device-code"))
        assertTrue(text.endsWith("\n\nNot signed in. Run grok login --device-code"))
    }

    @Test
    fun `LOST detail is trimmed to 300 characters and skipped when blank`() {
        every { messageResolver.get("ai.description.auth.lost", "en", "grok", "grok login --device-code") } returns "lost"

        val long = notifier.render(lost(detail = "x".repeat(1000)), "en")
        assertEquals("lost\n\n" + "x".repeat(300), long)

        val blank = notifier.render(lost(detail = "   "), "en")
        assertEquals("lost", blank)

        val none = notifier.render(lost(detail = null), "en")
        assertEquals("lost", none)
    }

    @Test
    fun `RESTORED sends the restored text without detail`() {
        every { messageResolver.get("ai.description.auth.restored", "en", "grok") } returns "🟢 grok ok"
        val builder = captureBuilder()

        notifier.onAuthEvent(
            DescriptionProviderAuthEvent(
                provider = "grok",
                state = DescriptionProviderAuthEvent.State.RESTORED,
                detail = null,
                recoveryHint = "grok login --device-code",
            ),
        )
        awaitAlert()

        assertEquals("🟢 grok ok", builder.captured.invoke("en"))
        assertFalse(builder.captured.invoke("en").contains("\n"))
    }

    @Test
    fun `delivery failures are swallowed`() {
        coEvery { telegramNotificationService.sendOwnerMessage(any()) } throws RuntimeException("boom")

        notifier.onAuthEvent(lost())
        awaitAlert()

        coVerify(exactly = 1) { telegramNotificationService.sendOwnerMessage(any()) }
    }
}
