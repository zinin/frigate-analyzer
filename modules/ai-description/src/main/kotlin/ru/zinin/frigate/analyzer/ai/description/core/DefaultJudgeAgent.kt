package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.JudgeAgent
import ru.zinin.frigate.analyzer.ai.description.api.JudgeOutcome
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import kotlin.time.toJavaDuration

class DefaultJudgeAgent(
    private val executor: VisionCallExecutor,
    private val parser: JudgeResponseParser,
) : JudgeAgent {
    override suspend fun judge(request: JudgeRequest): JudgeOutcome {
        val vision = VisionRequest(request.recordingId, request.frames, JudgeTask.instructions(request))
        val outcome = executor.execute(vision) { raw -> parser.parse(raw, request.maxSnoozeMinutes) }
        return JudgeOutcome(outcome.value, outcome.preset.id, outcome.preset.effectiveModel, outcome.elapsed.toJavaDuration())
    }
}
