package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress.Stage
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportModelsTest {
    @Test
    fun `renderProgress shows preparing stage`() {
        val result = renderProgress(Stage.PREPARING)
        assertContains(result, "Подготовка")
    }

    @Test
    fun `renderProgress shows done stage with checkmarks`() {
        val result = renderProgress(Stage.DONE)
        assertTrue(result.contains("✅"))
    }

    @Test
    fun `renderProgress shows annotating percent`() {
        val result = renderProgress(Stage.ANNOTATING, percent = 42, mode = ExportMode.ANNOTATED)
        assertContains(result, "42%")
    }

    @Test
    fun `renderProgress includes compression stage when compressing`() {
        val result = renderProgress(Stage.COMPRESSING, compressing = true)
        assertContains(result, "Сжатие видео")
    }

    @Test
    fun `renderProgress omits compression stage when not compressing`() {
        val result = renderProgress(Stage.MERGING)
        assertFalse(result.contains("Сжатие видео"))
    }
}
