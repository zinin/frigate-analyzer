package ru.zinin.frigate.analyzer.ai.description.claude

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudePromptBuilderTest {
    private val builder = ClaudePromptBuilder()

    private fun request(language: String = "en") =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames =
                listOf(
                    DescriptionRequest.FrameImage(0, ByteArray(1)),
                    DescriptionRequest.FrameImage(1, ByteArray(1)),
                ),
            language = language,
            shortMaxLength = 150,
            detailedMaxLength = 800,
        )

    private val paths =
        listOf(
            Path.of("/tmp/a/frame-0.jpg"),
            Path.of("/tmp/a/frame-1.jpg"),
        )

    @Test
    fun `includes language instruction for en`() {
        val prompt = builder.build(request("en"), paths)
        assertTrue(prompt.contains("in English"), "prompt must include English language hint")
    }

    @Test
    fun `includes language instruction for ru`() {
        val prompt = builder.build(request("ru"), paths)
        assertTrue(prompt.contains("in Russian"), "prompt must include Russian language hint")
    }

    @Test
    fun `includes numeric length limits`() {
        val prompt = builder.build(request(), paths)
        assertTrue(prompt.contains("150"), "prompt must include short length")
        assertTrue(prompt.contains("800"), "prompt must include detailed length")
    }

    @Test
    fun `includes file paths with at-prefix in frameIndex order`() {
        val prompt = builder.build(request(), paths)
        val idxFrame0 = prompt.indexOf("@/tmp/a/frame-0.jpg")
        val idxFrame1 = prompt.indexOf("@/tmp/a/frame-1.jpg")
        assertTrue(idxFrame0 > 0, "frame-0 path must be present with @-prefix")
        assertTrue(idxFrame1 > 0, "frame-1 path must be present with @-prefix")
        assertTrue(idxFrame0 < idxFrame1, "frame-0 must come before frame-1 in prompt")
    }

    @Test
    fun `sorts unordered frames by frameIndex before zip`() {
        // Важно: input frames в обратном порядке; stager возвращает пути отсортированно.
        // Builder должен выровнять порядок frames перед zip.
        val unorderedRequest =
            DescriptionRequest(
                recordingId = UUID.randomUUID(),
                frames =
                    listOf(
                        DescriptionRequest.FrameImage(1, ByteArray(1)),
                        DescriptionRequest.FrameImage(0, ByteArray(1)),
                    ),
                language = "en",
                shortMaxLength = 150,
                detailedMaxLength = 800,
            )
        val prompt = builder.build(unorderedRequest, paths)
        val idxFrame0 = prompt.indexOf("@/tmp/a/frame-0.jpg")
        val idxFrame1 = prompt.indexOf("@/tmp/a/frame-1.jpg")
        assertTrue(idxFrame0 > 0 && idxFrame1 > 0)
        assertTrue(idxFrame0 < idxFrame1, "builder must re-sort frames by frameIndex")
    }

    @Test
    fun `rejects unknown language code`() {
        assertFailsWith<IllegalStateException> {
            builder.build(request("de"), paths)
        }
    }

    @Test
    fun `requires JSON response with short and detailed keys`() {
        val prompt = builder.build(request(), paths)
        assertTrue(prompt.contains("\"short\""), "prompt must require \"short\" key")
        assertTrue(prompt.contains("\"detailed\""), "prompt must require \"detailed\" key")
        assertTrue(prompt.contains("JSON"), "prompt must mention JSON format")
    }

    @Test
    fun `is deterministic for same input`() {
        val r = request()
        assertTrue(builder.build(r, paths) == builder.build(r, paths))
    }
}
