package ru.zinin.frigate.analyzer.model.request

import ru.zinin.frigate.analyzer.model.dto.RecordingDto

data class ExtractFramesRemoteRequest(
    val recording: RecordingDto,
    val sceneThreshold: Double = 0.05,
    val minInterval: Double = 1.0,
    val maxFrames: Int = 50,
    val quality: Int = 85,
)
