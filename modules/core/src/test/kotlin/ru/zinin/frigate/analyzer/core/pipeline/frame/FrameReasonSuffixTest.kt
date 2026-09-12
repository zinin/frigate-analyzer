package ru.zinin.frigate.analyzer.core.pipeline.frame

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.response.ExtractedFrameData

/**
 * Once vision-api picks frames by motion, "why did this recording yield a single frame" is a
 * question the producer log has to answer on its own: the counts come from the server's `reason`.
 */
class FrameReasonSuffixTest {
    @Test
    fun `counts the frames per reason, in the order the server returned them`() {
        val frames = listOf(frame("first"), frame("grid"), frame("motion"), frame("grid"))

        assertThat(frameReasonSuffix(frames)).isEqualTo(" (first=1, grid=2, motion=1)")
    }

    @Test
    fun `stays empty when the server returned no frames`() {
        assertThat(frameReasonSuffix(emptyList())).isEmpty()
    }

    private fun frame(reason: String) =
        ExtractedFrameData(
            frameNumber = 0,
            timestamp = 0.0,
            imageBase64 = "",
            width = 1920,
            height = 1080,
            reason = reason,
        )
}
