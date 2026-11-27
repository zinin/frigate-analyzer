package ru.zinin.frigate.analyzer.core.pipeline.frame

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.core.JpegCodec
import ru.zinin.frigate.analyzer.core.config.properties.ChangedFramesConfig
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.model.dto.FrameData
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.UUID

/**
 * Картинки синтетические: однотонный фон с фигурами. Дневной кадр — цветной, ИК-кадр ночной камеры —
 * строго серый.
 */
class ChangedFrameDetectorTest {
    private val detector = ChangedFrameDetector(DetectProperties())
    private val day = Color(90, 140, 90)
    private val dark = Color(30, 30, 30)

    @Test
    fun `a new object in a daylight frame marks that frame`() {
        val result = detector.detect(frames(jpeg(day), jpeg(day) { box(400, 400, 160, 80, dark) }, jpeg(day)))

        assertThat(result.indexes).containsExactly(1)
        assertThat(result.skip).isNull()
    }

    @Test
    fun `a monochrome first frame skips the check`() {
        val night = Color(100, 100, 100)

        val result = detector.detect(frames(jpeg(night), jpeg(night) { box(400, 400, 160, 80, dark) }))

        assertThat(result.indexes).isEmpty()
        assertThat(result.skip).isEqualTo(ChangedFrames.Skip.MONOCHROME)
    }

    @Test
    fun `a slightly tinted grey frame is still monochrome`() {
        val tinted = Color(101, 100, 100)

        val result = detector.detect(frames(jpeg(tinted), jpeg(tinted) { box(400, 400, 160, 80, dark) }))

        assertThat(result.skip).isEqualTo(ChangedFrames.Skip.MONOCHROME)
    }

    @Test
    fun `a speck smaller than the minimal area is not a change`() {
        val result = detector.detect(frames(jpeg(day), jpeg(day) { box(400, 400, 10, 10, dark) }))

        assertThat(result.indexes).isEmpty()
        assertThat(result.skip).isNull()
    }

    @Test
    fun `a global brightness change is not a change`() {
        // Сдвиг в 40 уровней — выше порога различия пикселя: без компенсации кадр считался бы изменившимся.
        val result = detector.detect(frames(jpeg(day), jpeg(Color(130, 180, 130))))

        assertThat(result.indexes).isEmpty()
    }

    @Test
    fun `the camera clock strip is ignored`() {
        val result =
            detector.detect(
                frames(
                    jpeg(day) { box(900, 10, 300, 50, Color.WHITE) },
                    jpeg(day) { box(900, 10, 300, 50, dark) },
                ),
            )

        assertThat(result.indexes).isEmpty()
    }

    @Test
    fun `a change of most of the picture counts as changed`() {
        val result = detector.detect(frames(jpeg(day), jpeg(day) { box(0, 300, 1280, 660, dark) }))

        assertThat(result.indexes).containsExactly(1)
    }

    @Test
    fun `a single frame has nothing to compare`() {
        val result = detector.detect(frames(jpeg(day)))

        assertThat(result.skip).isEqualTo(ChangedFrames.Skip.NOTHING_TO_COMPARE)
    }

    @Test
    fun `an unreadable first frame gives the previous behaviour`() {
        val result = detector.detect(frames("not a jpeg".toByteArray(), jpeg(day)))

        assertThat(result.indexes).isEmpty()
        assertThat(result.skip).isEqualTo(ChangedFrames.Skip.FAILED)
    }

    @Test
    fun `an unreadable later frame is not marked and others still are`() {
        val result =
            detector.detect(
                frames(jpeg(day), "not a jpeg".toByteArray(), jpeg(day) { box(400, 400, 160, 80, dark) }),
            )

        assertThat(result.indexes).containsExactly(2)
    }

    @Test
    fun `a frame of another size is not compared`() {
        // Меньше рабочей ширины: без проверки размеров массивы сравнились бы молча, со сдвигом строк.
        val other = jpeg(day, width = 480, height = 360) { box(100, 100, 120, 60, dark) }

        val result = detector.detect(frames(jpeg(day), other))

        assertThat(result.indexes).isEmpty()
        assertThat(result.skip).isNull()
    }

    @Test
    fun `disabled by configuration`() {
        val disabled = ChangedFrameDetector(DetectProperties(changedFrames = ChangedFramesConfig(enabled = false)))

        val result = disabled.detect(frames(jpeg(day), jpeg(day) { box(400, 400, 160, 80, dark) }))

        assertThat(result.indexes).isEmpty()
        assertThat(result.skip).isEqualTo(ChangedFrames.Skip.DISABLED)
    }

    private fun frames(vararg bytes: ByteArray): List<FrameData> {
        val recordId = UUID.randomUUID()
        return bytes.mapIndexed { index, frame -> FrameData(recordId, index, frame) }
    }

    private fun jpeg(
        background: Color,
        width: Int = 1280,
        height: Int = 960,
        draw: Graphics2D.() -> Unit = {},
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = background
            graphics.fillRect(0, 0, width, height)
            graphics.draw()
        } finally {
            graphics.dispose()
        }
        return JpegCodec.encode(image, 0.95f)
    }

    private fun Graphics2D.box(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        fill: Color,
    ) {
        color = fill
        fillRect(x, y, w, h)
    }
}
