package ru.zinin.frigate.analyzer.ai.description.api

interface JudgeAgent {
    suspend fun judge(request: JudgeRequest): JudgeOutcome
}
