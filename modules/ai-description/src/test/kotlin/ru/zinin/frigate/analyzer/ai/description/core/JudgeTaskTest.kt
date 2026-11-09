package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JudgeTaskTest {
    private val request =
        JudgeRequest(
            recordingId = UUID.randomUUID(),
            camId = "cam2",
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            contextJson = """{"recording":{"cam":"cam2"}}""",
            language = "ru",
            maxSnoozeMinutes = 30,
        )

    private fun storyboard(
        missingBefore: Boolean = false,
        missingAfter: Boolean = false,
        detections: List<DescriptionRequest.DetectionMark> = listOf(DescriptionRequest.DetectionMark("bird", 0.6697, 5.0)),
    ) = DescriptionRequest.Storyboard(
        image = byteArrayOf(9),
        tiles = 16,
        stepSeconds = 0.66,
        durationSeconds = 10.0,
        detections = detections,
        missingBefore = missingBefore,
        missingAfter = missingAfter,
    )

    @Test
    fun `preamble names the camera and epilogue carries context, policy, snooze ceiling and language`() {
        val instructions = JudgeTask.instructions(request)
        assertTrue(instructions.preamble.contains("camera `cam2`"))
        assertTrue(instructions.epilogue.contains("""{"recording":{"cam":"cam2"}}"""))
        assertTrue(instructions.epilogue.contains("FALSE_POSITIVE"))
        assertTrue(instructions.epilogue.contains("When in doubt about a person, PUBLISH"))
        assertTrue(instructions.epilogue.contains("`snooze_minutes` (0–30)"))
        assertTrue(instructions.epilogue.contains("one sentence in Russian"))
        assertEquals(JudgeTask.SYSTEM_PROMPT, instructions.systemPrompt)
        assertEquals(JudgeTask.JSON_SCHEMA, instructions.jsonSchema)
    }

    /** Тот же довод, что в `DescriptionTaskTest`: судья ходит через тех же провайдеров. */
    @Test
    fun `the shared system prompt leaves the tool rule to the provider`() {
        val systemPrompt = JudgeTask.instructions(request).systemPrompt

        assertFalse(systemPrompt.contains("tool", ignoreCase = true))
        assertTrue(systemPrompt.contains("Answer only with the requested JSON object."))
    }

    @Test
    fun `is deterministic for the same input`() {
        assertEquals(JudgeTask.instructions(request), JudgeTask.instructions(request))
    }

    /**
     * Пока подписи писали провайдеры, судья видел `Frame N` по `frameIndex` под хронологическим заголовком;
     * без раскадровки так и осталось. Известное время кадра дописывается к подписи, чтобы модель видела,
     * сколько прошло между кадрами; кадр без времени остаётся `Frame N`.
     */
    @Test
    fun `frames are captioned Frame N in frameIndex order under the chronological header`() {
        val judge =
            request.copy(
                frames =
                    listOf(
                        DescriptionRequest.FrameImage(2, byteArrayOf(2), offsetSeconds = 5.0),
                        DescriptionRequest.FrameImage(0, byteArrayOf(0)),
                    ),
            )

        val vision = JudgeTask.visionRequest(judge)

        assertEquals(listOf("Frame 0", "Frame 2 at 5.0s"), vision.images.map { it.caption })
        assertEquals(listOf<Byte>(0, 2), vision.images.map { it.bytes.single() })
        assertEquals("Frames (in chronological order):", vision.instructions.imagesHeader)
    }

    /**
     * Сетка идёт первой и помечена своей ролью, кадры детектора — за ней по `frameIndex`, а не в порядке запроса.
     * Подпись кадра — индекс, по которому модель найдёт его в контексте, и время, если оно известно; неизвестное
     * время индексом не подменяется. Вступление отличает сетку без рамок от кадров с рамками и молчит о кадрах,
     * когда их нет; политика решения от сетки не зависит.
     */
    @Test
    fun `storyboard precedes chronological detail frames`() {
        val judge =
            request.copy(
                frames =
                    listOf(
                        DescriptionRequest.FrameImage(3, byteArrayOf(3), offsetSeconds = 5.0),
                        DescriptionRequest.FrameImage(0, byteArrayOf(0)),
                    ),
                storyboard = storyboard(),
            )

        val vision = JudgeTask.visionRequest(judge)

        assertEquals(listOf(VisionImage.Role.STORYBOARD, VisionImage.Role.FRAME, VisionImage.Role.FRAME), vision.images.map { it.role })
        assertEquals(
            listOf("Storyboard: 16 frames, 0.7s apart, left to right, top to bottom", "Frame 0", "Frame 3 at 5.0s"),
            vision.images.map { it.caption },
        )
        assertEquals(listOf<Byte>(9, 0, 3), vision.images.map { it.bytes.single() })
        assertEquals("Images:", vision.instructions.imagesHeader)
        assertTrue(vision.instructions.preamble.contains("closest to a detection"))
        assertTrue(vision.instructions.preamble.contains("boxes drawn on them"))
        assertTrue(vision.instructions.preamble.contains("The storyboard tiles carry no boxes"))
        assertFalse(vision.instructions.preamble.contains("full-resolution", ignoreCase = true))
        assertFalse(JudgeTask.instructions(judge.copy(frames = emptyList())).preamble.contains("remaining images"))
        assertTrue(vision.instructions.epilogue.contains("When in doubt about a person, PUBLISH"))
        assertEquals(JudgeTask.instructions(judge.copy(storyboard = null)).epilogue, vision.instructions.epilogue)
    }

    /**
     * Без строк о недоступной записи модель решила бы, что объект ушёл, когда запись просто кончилась; флаги
     * по одному, чтобы перепутанные строки не прошли. Время детекции — с точкой и одной цифрой после неё, как
     * на клетках. Клетка `detection` лишь ближайшая по времени: совпадения с кадром детектора промпт не обещает.
     */
    @Test
    fun `storyboard explains missing footage and detection times`() {
        val detections = listOf(DescriptionRequest.DetectionMark("bird", 0.6697, 5.04))
        val earlierMissing =
            JudgeTask.instructions(request.copy(storyboard = storyboard(missingBefore = true, detections = detections))).preamble
        val laterMissing = JudgeTask.instructions(request.copy(storyboard = storyboard(missingAfter = true))).preamble

        assertTrue(earlierMissing.contains("Earlier footage is not available."))
        assertFalse(earlierMissing.contains("Footage after"))
        assertTrue(laterMissing.contains("Footage after 10.0s is not available."))
        assertFalse(laterMissing.contains("Earlier footage"))
        assertTrue(earlierMissing.contains("The detector found: bird 0.67 at 5.0s."))
        assertTrue(earlierMissing.contains("not necessarily the frame the detector saw"))
        assertFalse(earlierMissing.contains("exact", ignoreCase = true))
    }

    /** Без сетки — прежний режим: то же вступление, тот же заголовок и `Frame N` у кадра без времени. */
    @Test
    fun `without storyboard keeps legacy header and unknown times`() {
        val judge =
            request.copy(
                frames =
                    listOf(
                        DescriptionRequest.FrameImage(1, byteArrayOf(1)),
                        DescriptionRequest.FrameImage(0, byteArrayOf(0)),
                    ),
            )

        val vision = JudgeTask.visionRequest(judge)

        assertEquals(listOf("Frame 0", "Frame 1"), vision.images.map { it.caption })
        assertEquals(listOf(VisionImage.Role.FRAME, VisionImage.Role.FRAME), vision.images.map { it.role })
        assertEquals("Frames (in chronological order):", vision.instructions.imagesHeader)
        assertEquals(
            "A YOLO detector flagged objects in a short recording from camera `cam2`. Your job is to decide whether " +
                "the household should be notified about this recording. The frames below have the detector's boxes " +
                "drawn on them. Context assembled from the database follows the frames.",
            vision.instructions.preamble,
        )
    }
}
