package ru.zinin.frigate.analyzer.model.dto

import java.time.Instant
import java.util.UUID

data class NewNotificationVerdict(
    val recordingId: UUID,
    val camId: String,
    val recordTimestamp: Instant,
    val stage: VerdictStage,
    val verdict: VerdictDecision,
    val reason: VerdictReason,
    val trackerReason: String,
    /** `person:1,car:1`, классы по алфавиту. */
    val classes: String,
    val confidence: Double? = null,
    val summary: String? = null,
    val wanted: String? = null,
    val snoozeUntil: Instant? = null,
    val presetId: String? = null,
    val model: String? = null,
    val latencyMs: Int? = null,
    val contextJson: String? = null,
    val error: String? = null,
)
