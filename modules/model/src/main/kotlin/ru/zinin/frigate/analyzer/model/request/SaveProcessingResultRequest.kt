package ru.zinin.frigate.analyzer.model.request

import ru.zinin.frigate.analyzer.model.dto.FrameData
import java.util.UUID

data class SaveProcessingResultRequest(
    val recordingId: UUID,
    val frames: List<FrameData> = emptyList(),
)
