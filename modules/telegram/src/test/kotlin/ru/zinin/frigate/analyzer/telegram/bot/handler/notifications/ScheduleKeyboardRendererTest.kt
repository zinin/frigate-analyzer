package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleKeyboardRendererTest {
    private val msg =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )
    private val renderer = ScheduleKeyboardRenderer(msg)

    private fun data(button: Any?): String = (button as CallbackDataInlineKeyboardButton).callbackData

    @Test
    fun `start picker has 4 hour rows of 6 plus back row`() {
        val rendered = renderer.startPicker("en")
        assertEquals(5, rendered.keyboard.keyboard.size)
        assertEquals(6, rendered.keyboard.keyboard[0].size)
        assertEquals("nfs:g:sched:s:0", data(rendered.keyboard.keyboard[0][0]))
        assertEquals("nfs:g:sched:s:23", data(rendered.keyboard.keyboard[3][5]))
        assertEquals("nfs:g:sched:home", data(rendered.keyboard.keyboard[4][0]))
    }

    @Test
    fun `start picker labels are zero-padded hours`() {
        val rendered = renderer.startPicker("en")
        assertEquals("00", (rendered.keyboard.keyboard[0][0] as CallbackDataInlineKeyboardButton).text)
        assertEquals("23", (rendered.keyboard.keyboard[3][5] as CallbackDataInlineKeyboardButton).text)
    }

    @Test
    fun `end picker embeds start hour in every callback`() {
        val rendered = renderer.endPicker(startHour = 23, showEqualWarning = false, lang = "en")
        assertEquals("nfs:g:sched:e:23:0", data(rendered.keyboard.keyboard[0][0]))
        assertEquals("nfs:g:sched:e:23:7", data(rendered.keyboard.keyboard[1][1]))
        assertTrue(rendered.text.contains("23:00"), "text=${rendered.text}")
        // Same grid shape as the start picker — the user must never be stranded without a way back.
        assertEquals(5, rendered.keyboard.keyboard.size)
        assertEquals("nfs:g:sched:home", data(rendered.keyboard.keyboard[4][0]))
    }

    @Test
    fun `end picker without warning has no warning line`() {
        val rendered = renderer.endPicker(startHour = 5, showEqualWarning = false, lang = "en")
        assertFalse(rendered.text.contains("must differ", ignoreCase = true), "text=${rendered.text}")
    }

    @Test
    fun `end picker with warning shows warning line`() {
        val rendered = renderer.endPicker(startHour = 5, showEqualWarning = true, lang = "en")
        assertTrue(rendered.text.contains("must differ", ignoreCase = true), "text=${rendered.text}")
    }

    @Test
    fun `zone screen offers presets, manual input and back`() {
        val rendered = renderer.zoneScreen(currentZone = "Europe/Moscow", lang = "en")
        assertTrue(rendered.text.contains("Europe/Moscow"), "text=${rendered.text}")
        // 8 presets in 4 rows of 2, then manual+back row
        assertEquals(5, rendered.keyboard.keyboard.size)
        assertEquals("nfs:g:sched:z:Europe/Kaliningrad", data(rendered.keyboard.keyboard[0][0]))
        assertEquals("nfs:g:sched:z:Asia/Vladivostok", data(rendered.keyboard.keyboard[3][1]))
        assertEquals("nfs:g:sched:zman", data(rendered.keyboard.keyboard[4][0]))
        assertEquals("nfs:g:sched:home", data(rendered.keyboard.keyboard[4][1]))
    }

    @Test
    fun `zone screen shows unset marker when zone missing`() {
        val rendered = renderer.zoneScreen(currentZone = null, lang = "en")
        assertTrue(rendered.text.contains("not set"), "text=${rendered.text}")
    }
}
