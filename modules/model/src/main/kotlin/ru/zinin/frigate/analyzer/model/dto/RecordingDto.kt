package ru.zinin.frigate.analyzer.model.dto

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class RecordingDto(
    val id: UUID,
    var creationTimestamp: Instant,
    var filePath: String,
    var fileCreationTimestamp: Instant,
    var camId: String,
    var recordDate: LocalDate,
    var recordTime: LocalTime,
    var recordTimestamp: Instant,
    var startProcessingTimestamp: Instant?,
    var processTimestamp: Instant?,
    var processAttempts: Int,
    var detectionsCount: Int,
    var analyzeTime: Int,
    var analyzedFramesCount: Int,
    var errorMessage: String?,
)
