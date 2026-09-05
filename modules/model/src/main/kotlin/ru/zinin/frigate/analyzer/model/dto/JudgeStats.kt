package ru.zinin.frigate.analyzer.model.dto

import java.time.Instant

data class StaticScore(
    val recordings: Long,
    val days: Long,
    val firstSeen: Instant?,
    val lastSeen: Instant?,
)

data class VerdictCountRow(
    val stage: String,
    val verdict: String,
    val reason: String,
    val count: Long,
)
