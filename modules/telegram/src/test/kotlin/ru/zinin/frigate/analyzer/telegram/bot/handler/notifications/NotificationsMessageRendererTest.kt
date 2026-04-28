package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.telegram.dto.NotificationsViewState
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationsMessageRendererTest {
    private val msg = MessageResolver(
        ReloadableResourceBundleMessageSource().apply {
            setBasename("classpath:messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
            setDefaultLocale(Locale.forLanguageTag("en"))
        },
    )
    private val renderer = NotificationsMessageRenderer(msg)

    @Test
    fun `user variant has 3 rows (recording, signal, close)`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = false,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = null,
                signalGlobalEnabled = null,
                language = "en",
            ),
        )
        assertEquals(3, rendered.keyboard.keyboard.size)
    }

    @Test
    fun `owner variant has 5 rows (recording user, signal user, recording global, signal global, close)`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = true,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = true,
                signalGlobalEnabled = true,
                language = "en",
            ),
        )
        assertEquals(5, rendered.keyboard.keyboard.size)
    }

    @Test
    fun `disabled per-user toggle button shows enable label`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = false,
                recordingUserEnabled = false,
                signalUserEnabled = true,
                recordingGlobalEnabled = null,
                signalGlobalEnabled = null,
                language = "en",
            ),
        )
        val recordingBtn = rendered.keyboard.keyboard[0][0] as CallbackDataInlineKeyboardButton
        assertTrue(recordingBtn.text.contains("Enable"), "button=${recordingBtn.text}")
        assertEquals("nfs:u:rec:1", recordingBtn.callbackData)
    }

    @Test
    fun `text mentions both ON state for user when both enabled`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = false,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = null,
                signalGlobalEnabled = null,
                language = "en",
            ),
        )
        val onCount = "ON".toRegex().findAll(rendered.text).count()
        assertTrue(onCount >= 2, "ON count=$onCount in text=${rendered.text}")
    }

    @Test
    fun `russian language uses russian state words`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = false,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = null,
                signalGlobalEnabled = null,
                language = "ru",
            ),
        )
        assertTrue(rendered.text.contains("ВКЛ"))
    }

    @Test
    fun `unknown language falls back to english strings`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = false,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = null,
                signalGlobalEnabled = null,
                language = "fr",
            ),
        )
        assertTrue(rendered.text.contains("ON"))
    }

    @Test
    fun `close button always present as last row with single button`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = true,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = true,
                signalGlobalEnabled = true,
                language = "en",
            ),
        )
        val lastRow = rendered.keyboard.keyboard.last()
        assertEquals(1, lastRow.size)
        val btn = lastRow[0] as CallbackDataInlineKeyboardButton
        assertEquals("nfs:close", btn.callbackData)
    }

    @Test
    fun `owner variant requires non-null global flags`() {
        val state = NotificationsViewState(
            isOwner = true,
            recordingUserEnabled = true,
            signalUserEnabled = true,
            recordingGlobalEnabled = null,
            signalGlobalEnabled = null,
            language = "en",
        )
        kotlin.runCatching { renderer.render(state) }
            .let { result -> assertTrue(result.isFailure, "renderer must throw for OWNER+null globals") }
    }
}
