package ru.zinin.frigate.analyzer.core.pipeline.frame

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.response.ExtractedFrameData
import java.util.Base64
import java.util.UUID

/**
 * Пайплайн перенумеровывает кадры сервера с нуля, и `timestamp` — единственная память о том, когда
 * кадр снят. Раскадровка AI-описания ставит по нему детекции на шкалу времени.
 */
class ToFrameDataTest {
    private val recordId = UUID.randomUUID()

    @Test
    fun `keeps the server's frame time and renumbers from zero`() {
        val frames =
            listOf(
                frame(frameNumber = 0, timestamp = 0.0, bytes = byteArrayOf(1)),
                frame(frameNumber = 31, timestamp = 2.48, bytes = byteArrayOf(2, 3)),
            )

        val result = toFrameData(recordId, frames)

        assertThat(result.map { it.frameIndex }).containsExactly(0, 1)
        assertThat(result.map { it.offsetSeconds }).containsExactly(0.0, 2.48)
        assertThat(result[1].frameBytes).isEqualTo(byteArrayOf(2, 3))
        assertThat(result).allMatch { it.recordId == recordId && it.detectResponse == null }
    }

    @Test
    fun `an empty server answer gives no frames`() {
        assertThat(toFrameData(recordId, emptyList())).isEmpty()
    }

    private fun frame(
        frameNumber: Int,
        timestamp: Double,
        bytes: ByteArray,
    ) = ExtractedFrameData(
        frameNumber = frameNumber,
        timestamp = timestamp,
        imageBase64 = Base64.getEncoder().encodeToString(bytes),
        width = 1920,
        height = 1080,
        reason = "grid",
    )
}
