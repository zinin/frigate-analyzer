package ru.zinin.frigate.analyzer.ai.description.api

import java.util.UUID

data class JudgeRequest(
    val recordingId: UUID,
    val camId: String,
    val frames: List<DescriptionRequest.FrameImage>,
    /** Контекст, собранный вызывающей стороной; модуль не знает, откуда он. */
    val contextJson: String,
    val language: String,
    val maxSnoozeMinutes: Int,
    /**
     * Раскадровка события; `null` — модель получает только [frames]. Времена сетки и `offsetSeconds` кадров
     * отсчитаны от одного нуля, который выбирает вызывающая сторона: задача их только печатает.
     */
    val storyboard: DescriptionRequest.Storyboard? = null,
)
