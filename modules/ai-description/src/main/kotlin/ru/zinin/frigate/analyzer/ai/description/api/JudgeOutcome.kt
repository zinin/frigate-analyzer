package ru.zinin.frigate.analyzer.ai.description.api

data class JudgeOutcome(
    val verdict: JudgeVerdict,
    val presetId: String,
    val model: String,
    val latency: java.time.Duration,
)
