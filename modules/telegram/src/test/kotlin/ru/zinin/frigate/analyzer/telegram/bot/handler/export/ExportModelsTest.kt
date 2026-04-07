package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress.Stage
import java.util.Locale
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportModelsTest {
    private val msg =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )

    @Test
    fun `renderProgress shows preparing stage`() {
        val result = renderProgress(Stage.PREPARING, msg = msg, lang = "ru")
        assertContains(result, "Подготовка")
    }

    @Test
    fun `renderProgress shows done stage with checkmarks`() {
        val result = renderProgress(Stage.DONE, msg = msg, lang = "ru")
        assertTrue(result.contains("✅"))
    }

    @Test
    fun `renderProgress shows annotating percent`() {
        val result = renderProgress(Stage.ANNOTATING, percent = 42, mode = ExportMode.ANNOTATED, msg = msg, lang = "ru")
        assertContains(result, "42%")
    }

    @Test
    fun `renderProgress includes compression stage when compressing`() {
        val result = renderProgress(Stage.COMPRESSING, compressing = true, msg = msg, lang = "ru")
        assertContains(result, "Сжатие видео")
    }

    @Test
    fun `renderProgress omits compression stage when not compressing`() {
        val result = renderProgress(Stage.MERGING, msg = msg, lang = "ru")
        assertFalse(result.contains("Сжатие видео"))
    }

    @Test
    fun `renderProgress shows English labels`() {
        val result = renderProgress(Stage.PREPARING, msg = msg, lang = "en")
        assertContains(result, "Preparing")
    }
}
