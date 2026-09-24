package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DescriptionTaskTest {
    private fun request(language: String = "en") =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = language,
            shortMaxLength = 150,
            detailedMaxLength = 800,
        )

    private fun storyboard(
        missingBefore: Boolean = false,
        missingAfter: Boolean = false,
        detections: List<DescriptionRequest.DetectionMark> = listOf(DescriptionRequest.DetectionMark("car", 0.87, 5.0)),
    ) = DescriptionRequest.Storyboard(
        image = byteArrayOf(9),
        tiles = 16,
        stepSeconds = 1.0,
        durationSeconds = 15.0,
        detections = detections,
        missingBefore = missingBefore,
        missingAfter = missingAfter,
    )

    @Test
    fun `preamble names the language`() {
        assertTrue(DescriptionTask.instructions(request("ru")).preamble.contains("Write both descriptions in Russian."))
        assertTrue(DescriptionTask.instructions(request("en")).preamble.contains("Write both descriptions in English."))
    }

    @Test
    fun `epilogue carries the JSON shape and the numeric limits`() {
        val epilogue = DescriptionTask.instructions(request()).epilogue
        assertTrue(epilogue.contains("""{"short": "...", "detailed": "..."}"""))
        assertTrue(epilogue.contains("must not exceed 150 characters"))
        assertTrue(epilogue.contains("must not exceed 800 characters"))
    }

    /** Одного ограничения длины мало: модель считает его потолком и пишет подпись, а не описание. */
    @Test
    fun `the short description asks for what happened, movement included`() {
        val epilogue = DescriptionTask.instructions(request()).epilogue

        assertTrue(epilogue.contains("\"short\": one to three sentences on what happened, including any movement"))
        assertTrue(epilogue.contains("must not exceed 150 characters"))
    }

    @Test
    fun `system prompt and schema are the fixed description ones`() {
        val instructions = DescriptionTask.instructions(request())
        assertEquals(DescriptionTask.SYSTEM_PROMPT, instructions.systemPrompt)
        assertEquals(DescriptionTask.JSON_SCHEMA, instructions.jsonSchema)
    }

    @Test
    fun `rejects unknown language code`() {
        assertFailsWith<IllegalStateException> { DescriptionTask.instructions(request("de")) }
    }

    /**
     * Правило про инструменты принадлежит провайдеру, а не задаче: Claude получает кадры ссылками
     * `@path` и видит их только вызовом Read, а Grok — готовыми image-блоками. Общий запрет
     * инструментов запрещал Claude единственный способ увидеть кадр, и модель отвечала либо прозой
     * (InvalidResponse), либо валидным JSON про недоступное изображение — вторая форма уходила
     * получателям вместо описания и не оставляла в логах ничего.
     */
    @Test
    fun `the shared system prompt leaves the tool rule to the provider`() {
        val systemPrompt = DescriptionTask.instructions(request()).systemPrompt

        assertFalse(systemPrompt.contains("tool", ignoreCase = true))
        assertTrue(systemPrompt.contains("Answer only with the requested JSON object."))
    }

    @Test
    fun `frames go in time order, captioned with their time when it is known`() {
        val request =
            request().copy(
                frames =
                    listOf(
                        DescriptionRequest.FrameImage(3, byteArrayOf(3), offsetSeconds = 7.5),
                        DescriptionRequest.FrameImage(1, byteArrayOf(1), offsetSeconds = 2.0),
                        DescriptionRequest.FrameImage(2, byteArrayOf(2)),
                    ),
            )

        val images = DescriptionTask.images(request)

        assertEquals(listOf("Frame at 2.0s", "Frame 2", "Frame at 7.5s"), images.map { it.caption })
        assertEquals(listOf<Byte>(1, 2, 3), images.map { it.bytes.single() })
    }

    @Test
    fun `frames alone are introduced by the chronological header`() {
        assertEquals("Frames (in chronological order):", DescriptionTask.instructions(request()).imagesHeader)
    }

    @Test
    fun `the vision request carries the recording id, the images and the instructions`() {
        val request = request()

        val vision = DescriptionTask.visionRequest(request)

        assertEquals(request.recordingId, vision.requestId)
        assertEquals(DescriptionTask.images(request), vision.images)
        assertEquals(DescriptionTask.instructions(request), vision.instructions)
    }

    @Test
    fun `the storyboard goes first, then the full-resolution frames in time order`() {
        val request =
            request().copy(
                frames =
                    listOf(
                        DescriptionRequest.FrameImage(4, byteArrayOf(4), offsetSeconds = 9.0),
                        DescriptionRequest.FrameImage(1, byteArrayOf(1), offsetSeconds = 5.0),
                    ),
                storyboard = storyboard(),
            )

        val images = DescriptionTask.images(request)

        assertEquals(
            listOf(
                "Storyboard: 16 frames, 1.0s apart, left to right, top to bottom",
                "Full-resolution frame at 5.0s",
                "Full-resolution frame at 9.0s",
            ),
            images.map { it.caption },
        )
        assertEquals(listOf<Byte>(9, 1, 4), images.map { it.bytes.single() })
        assertEquals("Images:", DescriptionTask.instructions(request).imagesHeader)
    }

    @Test
    fun `the storyboard preamble explains the timeline and lists detections with times`() {
        val detections =
            listOf(
                DescriptionRequest.DetectionMark("car", 0.87, 5.0),
                DescriptionRequest.DetectionMark("person", 0.71, 9.0),
            )

        val preamble = DescriptionTask.instructions(request().copy(storyboard = storyboard(detections = detections))).preamble

        assertTrue(preamble.contains("Write both descriptions in English."))
        assertTrue(preamble.contains("16 frames taken every 1.0s over 15.0s of footage, read left to right, top to bottom"))
        assertTrue(preamble.contains("The detector found: car 0.87 at 5.0s; person 0.71 at 9.0s."))
        assertTrue(preamble.contains("The remaining images are full-resolution frames for details."))
        assertTrue(preamble.contains("An object that stays in the same place in every tile is stationary."))
        assertFalse(preamble.contains("not available"))
    }

    /** Без этих строк модель выдумала бы, что машина «уехала», когда запись просто кончилась. */
    @Test
    fun `missing footage on either side is spelled out`() {
        val preamble =
            DescriptionTask.instructions(request().copy(storyboard = storyboard(missingBefore = true, missingAfter = true))).preamble

        assertTrue(preamble.contains("Earlier footage is not available."))
        assertTrue(preamble.contains("Footage after 15.0s is not available."))
    }

    /** По одному флагу за раз: перепутанные строки прошли бы проверку, где подняты оба. */
    @Test
    fun `missing earlier footage alone is spelled out alone`() {
        val preamble = DescriptionTask.instructions(request().copy(storyboard = storyboard(missingBefore = true))).preamble

        assertTrue(preamble.contains("Earlier footage is not available."))
        assertFalse(preamble.contains("Footage after"))
    }

    @Test
    fun `missing later footage alone is spelled out alone`() {
        val preamble = DescriptionTask.instructions(request().copy(storyboard = storyboard(missingAfter = true))).preamble

        assertTrue(preamble.contains("Footage after 15.0s is not available."))
        assertFalse(preamble.contains("Earlier footage"))
    }

    @Test
    fun `a detection without a time is listed without one`() {
        val detections = listOf(DescriptionRequest.DetectionMark("car", 0.87, null))

        val preamble = DescriptionTask.instructions(request().copy(storyboard = storyboard(detections = detections))).preamble

        assertTrue(preamble.contains("The detector found: car 0.87."))
    }

    @Test
    fun `the storyboard preamble mentions only what the request carries`() {
        val request = request().copy(frames = emptyList(), storyboard = storyboard(detections = emptyList()))

        val preamble = DescriptionTask.instructions(request).preamble

        assertEquals(listOf<Byte>(9), DescriptionTask.images(request).map { it.bytes.single() })
        assertFalse(preamble.contains("full-resolution"))
        assertFalse(preamble.contains("The detector found"))
    }

    @Test
    fun `a full-resolution frame without a time keeps its number`() {
        val request = request().copy(frames = listOf(DescriptionRequest.FrameImage(3, byteArrayOf(3))), storyboard = storyboard())

        assertEquals("Full-resolution frame 3", DescriptionTask.images(request).last().caption)
    }

    @Test
    fun `without a storyboard the preamble stays as it was`() {
        assertEquals(
            "You are analyzing surveillance camera frames captured during an object detection event.\n" +
                "Write both descriptions in English.",
            DescriptionTask.instructions(request("en")).preamble,
        )
    }
}
