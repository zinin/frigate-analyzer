package ru.zinin.frigate.analyzer.model.request

import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import java.nio.file.Path

data class ExtractFramesRequest(
    val ffmpegPath: String,
    val tempFolder: Path,
    val recording: RecordingDto,
    val threshold: Double = 0.05,
)
