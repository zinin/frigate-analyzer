package ru.zinin.frigate.analyzer.core.judge

import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.NotificationDecision
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity

data class JudgeCandidate(
    val recording: RecordingDto,
    val detections: List<DetectionEntity>,
    val decision: NotificationDecision,
    /** Кадры с ответами детектора (индекс, размер, детекции). JPEG на очереди не удерживается. */
    val frames: List<FrameData>,
    /** Кадры с рамками в порядке ранжирования визуализации — те же, что уйдут в Telegram. */
    val visualizedFrames: List<VisualizedFrameData>,
    val descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>)?,
)
