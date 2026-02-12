package ru.zinin.frigate.analyzer.model.request

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class CreateRecordingRequest(
    val filePath: String,
    val fileCreationTimestamp: Instant,
    val camId: String,
    val recordDate: LocalDate,
    val recordTime: LocalTime,
    val recordTimestamp: Instant,
)
