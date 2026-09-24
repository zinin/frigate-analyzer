package ru.zinin.frigate.analyzer.core.pipeline.frame

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.ImageSize
import java.util.UUID

/**
 * Время кадра идёт от producer-а к фасаду через это состояние, а `markCompleted` пересобирает кадр с ответом
 * детектора. Потеряй он `offsetSeconds`, каждая раскадровка молча стала бы «всей записью без пометок».
 * `FrameData.equals` сравнивает только ключи, поэтому время проверяется отдельно.
 */
class RecordingStateTest {
    private val recordId = UUID.randomUUID()

    @Test
    fun `a completed frame keeps its time`() {
        val state = RecordingState(recordId, listOf(FrameData(recordId, 0, byteArrayOf(1), offsetSeconds = 2.5)))
        val response = DetectResponse(emptyList(), 0, ImageSize(1920, 1080), "m")

        assertThat(state.markCompleted(0, response)).isTrue()

        val frame = state.getFrames().single()
        assertThat(frame.offsetSeconds).isEqualTo(2.5)
        assertThat(frame.detectResponse).isEqualTo(response)
    }
}
