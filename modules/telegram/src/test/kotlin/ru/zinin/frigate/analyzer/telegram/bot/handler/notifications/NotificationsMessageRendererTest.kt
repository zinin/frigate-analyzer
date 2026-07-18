package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.telegram.dto.NotificationsViewState
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationsMessageRendererTest {
    private val msg =
        MessageResolver(
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
        val rendered =
            renderer.render(
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
    fun `owner variant has 7 rows (2 user toggles, 2 global toggles, sched toggle, sched config, close)`() {
        val rendered =
            renderer.render(
                NotificationsViewState(
                    isOwner = true,
                    recordingUserEnabled = true,
                    signalUserEnabled = true,
                    recordingGlobalEnabled = true,
                    signalGlobalEnabled = true,
                    scheduleEnabled = false,
                    language = "en",
                ),
            )
        assertEquals(7, rendered.keyboard.keyboard.size)
    }

    @Test
    fun `disabled per-user toggle button shows enable label`() {
        val rendered =
            renderer.render(
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
        val rendered =
            renderer.render(
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
        val rendered =
            renderer.render(
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
        val rendered =
            renderer.render(
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
        val rendered =
            renderer.render(
                NotificationsViewState(
                    isOwner = true,
                    recordingUserEnabled = true,
                    signalUserEnabled = true,
                    recordingGlobalEnabled = true,
                    signalGlobalEnabled = true,
                    scheduleEnabled = false,
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
        val state =
            NotificationsViewState(
                isOwner = true,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = null,
                signalGlobalEnabled = null,
                scheduleEnabled = false,
                language = "en",
            )
        kotlin
            .runCatching { renderer.render(state) }
            .let { result -> assertTrue(result.isFailure, "renderer must throw for OWNER+null globals") }
    }

    private fun ownerState(
        scheduleEnabled: Boolean,
        scheduleWindow: String? = null,
        scheduleZone: String? = null,
    ) = NotificationsViewState(
        isOwner = true,
        recordingUserEnabled = true,
        signalUserEnabled = true,
        recordingGlobalEnabled = true,
        signalGlobalEnabled = true,
        scheduleEnabled = scheduleEnabled,
        scheduleWindow = scheduleWindow,
        scheduleZone = scheduleZone,
        language = "en",
    )

    @Test
    fun `owner text shows configured schedule window and zone`() {
        val rendered = renderer.render(ownerState(true, "00:00–07:00", "Europe/Moscow"))
        assertTrue(rendered.text.contains("00:00–07:00"), "text=${rendered.text}")
        assertTrue(rendered.text.contains("Europe/Moscow"), "text=${rendered.text}")
    }

    @Test
    fun `owner text shows off with stored window when disabled`() {
        val rendered = renderer.render(ownerState(false, "00:00–07:00", "Europe/Moscow"))
        assertTrue(rendered.text.contains("OFF"), "text=${rendered.text}")
        assertTrue(rendered.text.contains("00:00–07:00"), "text=${rendered.text}")
    }

    @Test
    fun `owner text shows plain off when nothing configured`() {
        val rendered = renderer.render(ownerState(false))
        assertTrue(rendered.text.contains("OFF"), "text=${rendered.text}")
        assertFalse(rendered.text.contains("00:00"), "text=${rendered.text}")
    }

    @Test
    fun `owner text shows misconfigured warning when enabled but window missing`() {
        val rendered = renderer.render(ownerState(true))
        assertTrue(rendered.text.contains("misconfigured", ignoreCase = true), "text=${rendered.text}")
    }

    @Test
    fun `owner text shows misconfigured warning when enabled but zone missing`() {
        val rendered = renderer.render(ownerState(true, scheduleWindow = "00:00–07:00"))
        assertTrue(rendered.text.contains("misconfigured", ignoreCase = true), "text=${rendered.text}")
        assertFalse(rendered.text.contains("00:00–07:00"), "text=${rendered.text}")
    }

    @Test
    fun `schedule toggle button emits explicit enable callback when disabled`() {
        val rendered = renderer.render(ownerState(false))
        val toggle = rendered.keyboard.keyboard[4][0] as CallbackDataInlineKeyboardButton
        assertEquals("nfs:g:sched:on", toggle.callbackData)
    }

    @Test
    fun `schedule toggle button emits explicit disable callback when enabled`() {
        val rendered = renderer.render(ownerState(true, "00:00–07:00", "Europe/Moscow"))
        val toggle = rendered.keyboard.keyboard[4][0] as CallbackDataInlineKeyboardButton
        assertEquals("nfs:g:sched:off", toggle.callbackData)
    }

    @Test
    fun `window and zone buttons sit in one row with cfg and zone callbacks`() {
        val rendered = renderer.render(ownerState(false))
        val row = rendered.keyboard.keyboard[5]
        assertEquals(2, row.size)
        assertEquals("nfs:g:sched:cfg", (row[0] as CallbackDataInlineKeyboardButton).callbackData)
        assertEquals("nfs:g:sched:zone", (row[1] as CallbackDataInlineKeyboardButton).callbackData)
    }

    @Test
    fun `non-owner keyboard has no schedule rows and text has no schedule line`() {
        val rendered =
            renderer.render(
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
        assertFalse(rendered.text.contains("schedule", ignoreCase = true), "text=${rendered.text}")
    }
}
