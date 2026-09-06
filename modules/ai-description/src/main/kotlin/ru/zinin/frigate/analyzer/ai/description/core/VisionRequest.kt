package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.util.UUID

/**
 * Инструкции одной vision-задачи. Провайдер вставляет кадры между [preamble] и [epilogue] своим
 * способом (Claude — ссылками `@path`, Grok — inline-блоками) и ничего о задаче не знает.
 */
data class VisionInstructions(
    val systemPrompt: String,
    val preamble: String,
    val epilogue: String,
    /** JSON Schema ответа для провайдеров со structured output; null = только текстом в epilogue. */
    val jsonSchema: String?,
)

data class VisionRequest(
    /** Id записи: имена временных файлов и строки логов. */
    val requestId: UUID,
    val frames: List<DescriptionRequest.FrameImage>,
    val instructions: VisionInstructions,
)
