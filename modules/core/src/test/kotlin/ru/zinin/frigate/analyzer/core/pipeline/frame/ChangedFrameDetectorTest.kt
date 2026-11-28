package ru.zinin.frigate.analyzer.core.pipeline.frame

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.core.JpegCodec
import ru.zinin.frigate.analyzer.core.config.properties.ChangedFramesConfig
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.model.dto.FrameData
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.IIOException
import javax.imageio.ImageIO

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

        // PNG: без потерь разброс каналов ровно 1, а не как после округления цветности в JPEG — на пороге.
        val result = detector.detect(frames(png(tinted), jpeg(tinted) { box(400, 400, 160, 80, dark) }))

        assertThat(result.skip).isEqualTo(ChangedFrames.Skip.MONOCHROME)
    }

    @Test
    fun `a weakly coloured daylight frame is still compared`() {
        // Разброс каналов ровно 4 — нижняя граница дневного кадра; PNG, как и выше, держит его без потерь.
        val pale = Color(102, 100, 98)

        val result = detector.detect(frames(png(pale), jpeg(pale) { box(400, 400, 160, 80, dark) }))

        assertThat(result.indexes).containsExactly(1)
        assertThat(result.skip).isNull()
    }

    @Test
    fun `a single-channel grey JPEG is monochrome`() {
        // Такой JPEG читается как TYPE_BYTE_GRAY — единственный путь через перевод кадра в BGR.
        val night = Color(100, 100, 100)
        val grey = BufferedImage.TYPE_BYTE_GRAY

        val result =
            detector.detect(
                frames(jpeg(night, type = grey), jpeg(night, type = grey) { box(400, 400, 160, 80, dark) }),
            )

        assertThat(result.skip).isEqualTo(ChangedFrames.Skip.MONOCHROME)
    }

    @Test
    fun `a speck smaller than the minimal area is not a change`() {
        val result = detector.detect(frames(jpeg(day), jpeg(day) { box(400, 400, 10, 10, dark) }))

        assertThat(result.indexes).isEmpty()
        assertThat(result.skip).isNull()
    }

    @Test
    fun `a region a little above the minimal area is a change`() {
        // 15×15 на рабочей ширине, после расширения 7×7 — около 520 пикселей: больше 300, но меньше 2000.
        val result = detector.detect(frames(jpeg(day), jpeg(day) { box(400, 400, 30, 30, dark) }))

        assertThat(result.indexes).containsExactly(1)
    }

    @Test
    fun `a thin line is not a change`() {
        // 2 пикселя на рабочей ширине: после размытия порог превышают два столбца, и открытие 3×3 их убирает.
        val result = detector.detect(frames(jpeg(day), jpeg(day) { box(640, 100, 4, 800, Color(40, 90, 40)) }))

        assertThat(result.indexes).isEmpty()
        assertThat(result.skip).isNull()
    }

    @Test
    fun `a global brightness change is not a change`() {
        // Сдвиг в 40 уровней — выше порога различия пикселя: без компенсации кадр считался бы изменившимся.
        val result = detector.detect(frames(jpeg(day), jpeg(Color(130, 180, 130))))

        assertThat(result.indexes).isEmpty()
        assertThat(result.skip).isNull()
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
        assertThat(result.skip).isNull()
    }

    @Test
    fun `a change in the clock strip does not shift the brightness of the rest`() {
        // Светлое и тёмное делят кадр почти пополам: пятно в полосе часов перетянуло бы медиану всего кадра
        // через провал гистограммы, и общий сдвиг яркости пометил бы весь кадр.
        val light = Color(170, 200, 170)
        val shade = Color(55, 85, 55)

        val result =
            detector.detect(
                frames(
                    jpeg(light) { box(0, 80, 1280, 464, shade) },
                    jpeg(light) {
                        box(0, 80, 1280, 464, shade)
                        box(128, 8, 768, 56, shade)
                    },
                ),
            )

        assertThat(result.indexes).isEmpty()
        assertThat(result.skip).isNull()
    }

    @Test
    fun `a change of most of the picture counts as changed`() {
        val result = detector.detect(frames(jpeg(day), jpeg(day) { box(0, 300, 1280, 660, dark) }))

        assertThat(result.indexes).containsExactly(1)
    }

    @Test
    fun `small specks over much of the picture count as changed`() {
        // Метель: каждое пятнышко меньше наименьшей области, но вместе они занимают заметную часть кадра.
        val snow =
            jpeg(day) {
                for (x in 12 until 1280 step 32) {
                    for (y in 12 until 960 step 32) {
                        box(x, y, 8, 8, dark)
                    }
                }
            }

        val result = detector.detect(frames(jpeg(day), snow))

        assertThat(result.indexes).containsExactly(1)
    }

    @Test
    fun `the warning about unreadable frames names the first cause`() {
        val recordId = UUID.randomUUID()

        val message =
            unreadableFramesWarning(
                recordId,
                listOf(FrameFailure(2, "IIOException: broken"), FrameFailure(3, "not a recognised image")),
            )

        assertThat(message).contains("[2, 3]", recordId.toString(), "IIOException: broken")
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
    fun `an error while reading the first frame gives the previous behaviour`() {
        val first = jpeg(day)

        val result =
            decodeFailing(first, NoClassDefFoundError("Could not initialize class java.awt.image.BufferedImage")) {
                detector.detect(frames(first, jpeg(day) { box(400, 400, 160, 80, dark) }))
            }

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
    fun `a later frame the JPEG reader fails on is not marked and others still are`() {
        val broken = jpegOfEmptyImage()
        // Reader узнаёт JPEG и бросает, а не возвращает null, как на байтах, которые не узнал никто.
        assertThatThrownBy { JpegCodec.decode(broken) }.isInstanceOf(IIOException::class.java)

        val result = detector.detect(frames(jpeg(day), broken, jpeg(day) { box(400, 400, 160, 80, dark) }))

        assertThat(result.indexes).containsExactly(2)
    }

    @Test
    fun `a later frame that throws an error is not marked and others still are`() {
        val broken = jpeg(day) { box(100, 600, 160, 80, dark) }

        val result =
            decodeFailing(broken, UnsatisfiedLinkError("no javajpeg in java.library.path")) {
                detector.detect(frames(jpeg(day), broken, jpeg(day) { box(400, 400, 160, 80, dark) }))
            }

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
    fun `a frame of another size is not compared even when it shrinks to the same working size`() {
        // 1920×1440 уменьшается до тех же 640×480, что и 1280×960, но это другой кадр.
        val other = jpeg(day, width = 1920, height = 1440) { box(600, 600, 240, 120, dark) }

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

    /** [JpegCodec.decode] бросает [error] на этом самом массиве [bytes]; остальные вызовы идут в настоящий кодек. */
    private fun <T> decodeFailing(
        bytes: ByteArray,
        error: Throwable,
        block: () -> T,
    ): T {
        mockkObject(JpegCodec)
        try {
            every { JpegCodec.decode(match { it === bytes }) } throws error
            return block()
        } finally {
            unmockkObject(JpegCodec)
        }
    }

    /** Настоящий JPEG, в заголовке которого высота кадра 0: reader его узнаёт, но прочитать не может. */
    private fun jpegOfEmptyImage(): ByteArray {
        val bytes = jpeg(day)
        val sof = (0 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xC0.toByte() }
        // После маркера SOF0: длина (2 байта), точность (1), высота (2).
        bytes[sof + 5] = 0
        bytes[sof + 6] = 0
        return bytes
    }

    private fun jpeg(
        background: Color,
        width: Int = 1280,
        height: Int = 960,
        type: Int = BufferedImage.TYPE_INT_RGB,
        draw: Graphics2D.() -> Unit = {},
    ): ByteArray {
        val image = BufferedImage(width, height, type)
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

    private fun png(
        color: Color,
        width: Int = 1280,
        height: Int = 960,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
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
